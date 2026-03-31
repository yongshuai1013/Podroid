/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * QEMU engine for Podroid. Manages the VM lifecycle, serial console I/O,
 * and a persistent terminal emulator that handles terminal queries (DA, DSR,
 * window size) even when the UI is detached.
 */
package com.excp.podroid.engine

import android.content.Context
import android.util.Log
import com.excp.podroid.data.repository.PortForwardRule
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodroidQemu @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    val state: StateFlow<VmState> = _state.asStateFlow()

    private val _consoleText = MutableStateFlow("")
    val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    private val _bootStage = MutableStateFlow("")
    val bootStage: StateFlow<String> = _bootStage.asStateFlow()

    /** Persistent terminal emulator that outlives the UI. */
    var terminalEmulator: TerminalEmulator? = null
        private set

    @Volatile
    var process: Process? = null
        private set

    @Volatile
    private var consoleOutputStream: OutputStream? = null

    private val writeQueue = LinkedBlockingQueue<ByteArray>()

    /** Current terminal dimensions for responding to size queries. */
    @Volatile var terminalRows = 24
    @Volatile var terminalCols = 80

    /** Coroutine scope for I/O threads — cancelled on stop(). */
    private var ioScope: CoroutineScope? = null

    fun writeToConsole(data: ByteArray) {
        writeQueue.offer(data)
    }

    fun updateTerminalSize(cols: Int, rows: Int) {
        terminalCols = cols
        terminalRows = rows
        terminalEmulator?.resize(cols, rows)
    }

    /** Callback for raw console output. Used by TerminalViewModel for UI refresh. */
    @Volatile
    var onConsoleOutput: ((ByteArray, Int) -> Unit)? = null

    val qmpClient: QmpClient by lazy {
        QmpClient("${context.filesDir.absolutePath}/qmp.sock")
    }

    fun start() = start(emptyList())

    fun start(portForwards: List<PortForwardRule>, ramMb: Int = 1024, cpus: Int = 4) {
        if (_state.value is VmState.Starting || _state.value is VmState.Running) {
            Log.w(TAG, "start() called while VM is ${_state.value}, ignoring")
            return
        }

        val qemuExe = qemuExecutable() ?: run {
            _state.value = VmState.Error("QEMU binary not found.")
            return
        }

        ensureStorageImage()

        _state.value = VmState.Starting
        _consoleText.value = ""
        _bootStage.value = "Starting QEMU..."

        try {
            val cmd = buildCommand(qemuExe, portForwards, ramMb, cpus)
            Log.d(TAG, "Launching: ${cmd.joinToString(" ")}")

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val filesDir = context.filesDir.absolutePath

            val pb = ProcessBuilder(cmd).directory(context.filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeDir:$filesDir"

            val proc = pb.start()
            process = proc
            consoleOutputStream = proc.outputStream
            _bootStage.value = "Booting kernel..."

            // Create coroutine scope for I/O
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ioScope = scope

            // Writer coroutine — drains writeQueue to QEMU stdin
            scope.launch {
                try {
                    while (isActive && process?.isAlive == true) {
                        val data = writeQueue.poll(500, TimeUnit.MILLISECONDS)
                        if (data != null) {
                            consoleOutputStream?.write(data)
                            consoleOutputStream?.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Console writer ended: ${e.message}")
                }
            }

            // Initialize persistent terminal emulator.
            // write() routes emulator responses (DA, DSR, cursor position) back
            // to the VM so TUI apps like nvim receive the answers they need.
            val qemuOutput = object : TerminalOutput() {
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    writeToConsole(data.copyOfRange(offset, offset + count))
                }
                override fun titleChanged(oldTitle: String?, newTitle: String?) {}
                override fun onCopyTextToClipboard(text: String?) {}
                override fun onPasteTextFromClipboard() {}
                override fun onBell() {}
                override fun onColorsChanged() {}
            }
            terminalEmulator = TerminalEmulator(
                qemuOutput, 80, 24, 2000, NoOpSessionClient
            )

            // Console reader — reads stdout, feeds emulator, detects boot stages
            val logFile = File(context.filesDir, "console.log")
            logFile.delete()
            scope.launch {
                FileOutputStream(logFile, true).use { logOut ->
                    val buf = ByteArray(4096)
                    val consoleBuilder = StringBuilder()
                    val input = proc.inputStream
                    try {
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            val chunk = buf.copyOf(n)
                            val text = String(chunk)

                            consoleBuilder.append(text)
                            _consoleText.value = consoleBuilder.toString()

                            logOut.write(chunk, 0, n)
                            logOut.flush()

                            // Feed persistent emulator (renders screen buffer)
                            terminalEmulator?.append(chunk, n)

                            detectBootStage(text)
                            onConsoleOutput?.invoke(chunk, n)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Console reader ended: ${e.message}")
                    }
                }
            }

            // Stderr drain
            scope.launch {
                try {
                    val buf = ByteArray(4096)
                    val err = proc.errorStream
                    while (isActive) {
                        val n = err.read(buf)
                        if (n < 0) break
                        Log.d("PodroidVM-err", String(buf, 0, n).trimEnd().take(300))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Stderr drain ended: ${e.message}")
                }
            }

            // Wait briefly, then mark as running if alive
            Thread.sleep(2000)
            if (proc.isAlive) {
                Log.d(TAG, "QEMU is running")
                _state.value = VmState.Running
            } else {
                val exitCode = proc.waitFor()
                Log.e(TAG, "QEMU died immediately, exit code: $exitCode")
                _state.value = VmState.Error("QEMU exited with code $exitCode")
                cleanup()
                return
            }

            val exitCode = proc.waitFor()
            Log.d(TAG, "QEMU exited with code: $exitCode")
            cleanup()
            _state.value = if (exitCode == 0) VmState.Stopped else VmState.Error("Exit code $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QEMU", e)
            _state.value = VmState.Error(e.message ?: "Unknown error")
            cleanup()
        }
    }

    private fun detectBootStage(text: String) {
        when {
            "Mounting persistent" in text || "Formatting" in text ->
                _bootStage.value = "Mounting storage..."
            "overlay" in text ->
                _bootStage.value = "Setting up overlay..."
            "Mounting filesystems" in text ->
                _bootStage.value = "Mounting filesystems..."
            "Loading kernel modules" in text ->
                _bootStage.value = "Loading kernel modules..."
            "Configuring containers" in text ->
                _bootStage.value = "Configuring containers..."
            "Waiting for network" in text ->
                _bootStage.value = "Waiting for network..."
            "Found" in text && "eth" in text ->
                _bootStage.value = "Network found"
            "Internet:" in text ->
                _bootStage.value = "Checking internet..."
            "Podroid" in text && "Alpine" in text ->
                _bootStage.value = "Almost ready..."
            "Ready!" in text ->
                _bootStage.value = "Ready"
        }
    }

    fun stop() {
        try {
            writeToConsole("sync\n".toByteArray())
            Thread.sleep(500)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to send sync: ${e.message}")
        }

        process?.destroy()
        try { Thread.sleep(1000) } catch (_: Exception) {}
        if (process?.isAlive == true) {
            process?.destroyForcibly()
        }
        cleanup()
        _state.value = VmState.Stopped
    }

    private fun cleanup() {
        ioScope?.cancel()
        ioScope = null
        process = null
        consoleOutputStream = null
        writeQueue.clear()
        terminalEmulator = null
        onConsoleOutput = null
        _bootStage.value = ""
    }

    private fun buildCommand(
        qemuExe: File,
        portForwards: List<PortForwardRule>,
        ramMb: Int,
        cpus: Int,
    ): List<String> {
        val args = mutableListOf<String>()

        args += "-M"; args += "virt"
        args += "-cpu"; args += "max"
        args += "-smp"; args += "$cpus"
        args += "-m"; args += "$ramMb"
        args += "-accel"; args += "tcg,thread=multi"

        val kernelPath = File(context.filesDir, "vmlinuz-virt")
        val initrdPath = File(context.filesDir, "initrd.img")

        if (kernelPath.exists()) {
            args += "-kernel"; args += kernelPath.absolutePath
            args += "-append"; args += "console=ttyAMA0 loglevel=1 quiet"
        } else {
            Log.w(TAG, "Kernel not found!")
        }

        if (initrdPath.exists()) {
            args += "-initrd"; args += initrdPath.absolutePath
        } else {
            Log.w(TAG, "Initrd not found!")
        }

        val storagePath = File(context.filesDir, "storage.img")
        if (storagePath.exists()) {
            args += "-device"; args += "virtio-blk-pci,drive=drive1"
            args += "-drive"; args += "file=${storagePath.absolutePath},if=none,id=drive1,format=raw"
        }

        val netdevArg = buildString {
            append("user,id=net0")
            for (rule in portForwards) {
                append(",hostfwd=${rule.protocol}::${rule.hostPort}-:${rule.guestPort}")
            }
        }
        args += "-netdev"; args += netdevArg
        args += "-device"; args += "virtio-net,netdev=net0"

        args += "-serial"; args += "stdio"
        args += "-display"; args += "none"
        args += "-qmp"
        args += "unix:${context.filesDir.absolutePath}/qmp.sock,server,nowait"
        args += "-overcommit"; args += "mem-lock=off"

        return listOf(qemuExe.absolutePath) + args
    }

    private fun ensureStorageImage() {
        val storageFile = File(context.filesDir, "storage.img")
        if (!storageFile.exists()) {
            try {
                java.io.RandomAccessFile(storageFile, "rw").use { raf ->
                    raf.setLength(2L * 1024 * 1024 * 1024)
                }
                Log.d(TAG, "Created storage.img")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create storage.img", e)
            }
        }
    }

    private fun qemuExecutable(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val exe = File(nativeDir, "libqemu-system-aarch64.so")
        return if (exe.exists()) exe else null
    }

    companion object {
        private const val TAG = "PodroidQemu"
    }
}

/** No-op TerminalSessionClient for the persistent emulator (no UI attached). */
private object NoOpSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
