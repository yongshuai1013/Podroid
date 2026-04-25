/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * QEMU engine for Podroid. Manages the VM lifecycle and exposes two Unix
 * sockets for the terminal layer:
 *
 *   serial.sock — QEMU serial chardev (ttyAMA0 in the VM). The podroid-bridge
 *                 binary connects here for bidirectional terminal I/O.
 *
 *   ctrl.sock   — QEMU virtio-console chardev (hvc0 in the VM). The bridge
 *                 writes "RESIZE rows cols\n" here on SIGWINCH; the VM daemon
 *                 calls stty on ttyAMA0 which triggers SIGWINCH in the VM.
 *
 * Boot monitoring: PodroidQemu connects to serial.sock and reads until the VM
 * reaches the "Ready" stage (or until releaseSerial() is called). The bridge
 * then takes over the connection for interactive terminal use.
 */
package com.excp.podroid.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.excp.podroid.data.repository.PortForwardRule
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("StaticFieldLeak") // ApplicationContext — lives as long as the process, no leak
@Singleton
class PodroidQemu @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: com.excp.podroid.data.repository.SettingsRepository,
) {
    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    val state: StateFlow<VmState> = _state.asStateFlow()

    private val _consoleText = MutableStateFlow("")
    val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    private val _bootStage = MutableStateFlow("")

    private var _terminalSession: TerminalSession? = null
    private var _terminalSessionAttached = false

    val terminalSession: TerminalSession? get() = _terminalSession
    val terminalSessionAttached: Boolean get() = _terminalSessionAttached
    val bootStage: StateFlow<String> = _bootStage.asStateFlow()

    @Volatile
    var process: Process? = null
        private set

    /** Unix socket paths exposed to TerminalViewModel for the bridge binary. */
    val serialSockPath: String get() = "${context.filesDir.absolutePath}/serial.sock"
    val terminalSockPath: String get() = "${context.filesDir.absolutePath}/terminal.sock"
    val ctrlSockPath: String get() = "${context.filesDir.absolutePath}/ctrl.sock"

    /** Boot-monitoring socket connection. Closed when the bridge takes over. */
    @Volatile
    private var bootSocket: LocalSocket? = null

    private val qmpSocketPath: String get() = "${context.filesDir.absolutePath}/qmp.sock"

    val qmpClient: QmpClient by lazy { QmpClient(qmpSocketPath) }

    private var ioScope: CoroutineScope? = null

    private val consoleBuilder = StringBuilder()
    private val maxConsoleSize = 64 * 1024

    /**
     * Proxy TerminalSessionClient — delegates to whatever real client is set.
     * Lets us create the bridge session at boot-complete time (before the
     * terminal UI exists) and plug in the real ViewModel client later.
     */
    @Volatile
    var sessionClientDelegate: TerminalSessionClient? = null

    private val proxySessionClient = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { sessionClientDelegate?.onTextChanged(s) }
        override fun onTitleChanged(s: TerminalSession) { sessionClientDelegate?.onTitleChanged(s) }
        override fun onSessionFinished(s: TerminalSession) { sessionClientDelegate?.onSessionFinished(s) }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) { sessionClientDelegate?.onCopyTextToClipboard(s, text) }
        override fun onPasteTextFromClipboard(s: TerminalSession?) { sessionClientDelegate?.onPasteTextFromClipboard(s) }
        override fun onBell(s: TerminalSession) { sessionClientDelegate?.onBell(s) }
        override fun onColorsChanged(s: TerminalSession) { sessionClientDelegate?.onColorsChanged(s) }
        override fun onTerminalCursorStateChange(state: Boolean) { sessionClientDelegate?.onTerminalCursorStateChange(state) }
        override fun getTerminalCursorStyle(): Int = sessionClientDelegate?.terminalCursorStyle ?: 0
        override fun logError(tag: String?, msg: String?) { Log.e(tag ?: TAG, msg ?: "") }
        override fun logWarn(tag: String?, msg: String?) { Log.w(tag ?: TAG, msg ?: "") }
        override fun logInfo(tag: String?, msg: String?) { Log.i(tag ?: TAG, msg ?: "") }
        override fun logDebug(tag: String?, msg: String?) { Log.d(tag ?: TAG, msg ?: "") }
        override fun logVerbose(tag: String?, msg: String?) { Log.v(tag ?: TAG, msg ?: "") }
        override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) { Log.e(tag ?: TAG, msg, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "Stack trace", e) }
    }

    /**
     * Release the boot-monitoring serial connection so podroid-bridge can connect.
     */
    fun releaseSerial() {
        val sock = bootSocket ?: return
        Log.d(TAG, "Releasing boot monitor socket")
        bootSocket = null
        try {
            // Signal EOF to the monitor loop
            sock.shutdownInput()
            sock.shutdownOutput()
            sock.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing boot socket: ${e.message}")
        }
    }

    /**
     * Replay the portion of boot serial output that arrived after "Ready!" into
     * a freshly created terminal emulator.
     */
    private fun replayBootOutput(sess: TerminalSession) {
        val console = _consoleText.value
        val readyIdx = console.indexOf("Ready!")
        if (readyIdx < 0) return
        val lineEnd = console.indexOf('\n', readyIdx)
        if (lineEnd < 0) return
        val postReady = console.substring(lineEnd + 1)
        if (postReady.isEmpty()) return
        val bytes = postReady.toByteArray(Charsets.UTF_8)
        try {
            sess.emulator?.append(bytes, bytes.size)
            Log.d(TAG, "Replayed ${bytes.size}B of post-Ready output to emulator")
        } catch (e: Exception) {
            Log.w(TAG, "Could not replay boot output: ${e.message}")
        }
    }

    private fun autoStartBridge() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (_terminalSession != null) return@post
            if (_state.value !is VmState.Running && _state.value !is VmState.Starting) return@post

            // Bridge connects to terminal.sock (virtio-console, separate from serial).
            // No handoff with the boot monitor needed — they use different sockets.
            val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
            if (!bridgeExe.exists()) return@post

            val sess = TerminalSession(
                bridgeExe.absolutePath,
                context.filesDir.absolutePath,
                arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
                null,
                2000,
                proxySessionClient,
            )
            sess.updateSize(80, 24)
            _terminalSession = sess
            _terminalSessionAttached = false
            Log.d(TAG, "Bridge auto-started on terminal.sock")
        }
    }

    fun createTerminalSession(client: TerminalSessionClient): TerminalSession {
        sessionClientDelegate = client

        // Session already auto-started during boot — just return it
        if (_terminalSession != null) {
            _terminalSessionAttached = true
            Log.d(TAG, "Returning pre-started terminal session")
            return _terminalSession!!
        }

        // Fallback: create session now
        val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
        if (!bridgeExe.exists()) {
            throw IllegalStateException("podroid-bridge not found at ${bridgeExe.absolutePath}")
        }

        val sess = TerminalSession(
            bridgeExe.absolutePath,
            context.filesDir.absolutePath,
            arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
            null,
            2000,
            proxySessionClient,
        )

        sess.updateSize(80, 24)
        replayBootOutput(sess)

        _terminalSession = sess
        _terminalSessionAttached = true
        Log.d(TAG, "Terminal session created in Qemu singleton")
        return sess
    }

    fun attachTerminalView() {
        _terminalSessionAttached = true
    }

    fun start() = start(emptyList())

    fun start(
        portForwards: List<PortForwardRule>,
        ramMb: Int = 512,
        cpus: Int = 1,
        sshEnabled: Boolean = false,
        androidIp: String = "unknown",
    ) {
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
        consoleBuilder.clear()
        _consoleText.value = ""
        _bootStage.value = "Starting QEMU..."

        // Clean up stale sockets from a previous run. qmp.sock must be
        // included — a leftover file from a crashed QEMU prevents the new
        // process from binding its QMP server socket.
        File(serialSockPath).delete()
        File(terminalSockPath).delete()
        File(ctrlSockPath).delete()
        File(qmpSocketPath).delete()

        try {
            val cmd = buildCommand(qemuExe, portForwards, ramMb, cpus, sshEnabled, androidIp)
            Log.d(TAG, "Launching: ${cmd.joinToString(" ")}")

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(cmd).directory(context.filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeDir:${context.filesDir.absolutePath}"

            val proc = pb.start()
            process = proc
            _bootStage.value = "Booting kernel..."

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ioScope = scope

            // Drain QEMU's own stderr (not serial — just QEMU startup messages)
            scope.launch {
                try {
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val n = proc.errorStream.read(buf)
                        if (n < 0) break
                        Log.d("PodroidVM-err", String(buf, 0, n).trimEnd().take(300))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Stderr drain ended: ${e.message}")
                }
            }

            // Boot monitor — connects to serial.sock once QEMU creates it
            scope.launch { monitorBootSerial(proc) }

            val startMs = System.currentTimeMillis()
            val timeoutMs = 10_000L
            var socketsReady = false
            while (System.currentTimeMillis() - startMs < timeoutMs) {
                if (!proc.isAlive) {
                    val exitCode = proc.exitValue()
                    Log.e(TAG, "QEMU died during startup, exit code: $exitCode")
                    _state.value = VmState.Error("QEMU exited with code $exitCode")
                    cleanup()
                    return
                }
                if (File(serialSockPath).exists() && File(qmpSocketPath).exists()) {
                    Log.d(TAG, "QEMU sockets ready after ${System.currentTimeMillis() - startMs}ms")
                    socketsReady = true
                    break
                }
                Thread.sleep(200)
            }
            if (!socketsReady) {
                Log.e(TAG, "Socket timeout — QEMU sockets not ready after ${timeoutMs}ms")
                proc.destroyForcibly()
                throw RuntimeException("QEMU failed to create sockets within ${timeoutMs / 1000}s")
            }

            // State stays Starting — boot monitor will set Running when "Ready!" is detected
            scope.launch {
                delay(60_000)
                if (_state.value is VmState.Starting) {
                    Log.w(TAG, "Boot timeout fallback → forcing Running state")
                    _bootStage.value = "Ready"
                    _state.value = VmState.Running
                    autoStartBridge()
                }
            }

            // Block until QEMU exits (keeps the calling service coroutine alive)
            val exitCode = proc.waitFor()
            Log.d(TAG, "QEMU exited: $exitCode")
            cleanup()
            _state.value = if (exitCode == 0) VmState.Stopped else VmState.Error("Exit code $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QEMU", e)
            _state.value = VmState.Error(e.message ?: "Unknown error")
            cleanup()
        }
    }

    /**
     * Connects to serial.sock and reads boot output until:
     *   - The "Ready" stage is detected, or
     *   - releaseSerial() is called (bootSocket closed externally), or
     *   - The coroutine is cancelled.
     *
     * Writes all output to console.log for the test-deploy.sh validator and
     * publishes boot stage updates to the UI.
     */
    private suspend fun monitorBootSerial(proc: Process) {
        val sockFile = File(serialSockPath)

        // Wait up to 8s for QEMU to create the socket
        var waited = 0
        while (waited < 8000 && !sockFile.exists() && proc.isAlive) {
            delay(100)
            waited += 100
        }
        if (!sockFile.exists()) {
            Log.w(TAG, "serial.sock not found after ${waited}ms — boot detection disabled")
            return
        }

        val sock = LocalSocket()
        try {
            sock.connect(LocalSocketAddress(serialSockPath, LocalSocketAddress.Namespace.FILESYSTEM))
            bootSocket = sock
            Log.d(TAG, "Boot monitor connected to serial.sock")
        } catch (e: Exception) {
            Log.w(TAG, "Boot monitor could not connect to serial.sock: ${e.message}")
            sock.close()
            return
        }

        val logFile = File(context.filesDir, "console.log")
        logFile.delete()

        try {
            FileOutputStream(logFile, false).use { logOut ->
                val buf = ByteArray(4096)
                val input = sock.inputStream
                while (true) {
                    val n = try {
                        input.read(buf)
                    } catch (_: Exception) {
                        break // socket closed by releaseSerial() or VM exit
                    }
                    if (n < 0) break

                    val chunk = buf.copyOf(n)
                    val text = String(chunk, Charsets.UTF_8)

                    consoleBuilder.append(text)
                    if (consoleBuilder.length > maxConsoleSize) {
                        consoleBuilder.delete(0, consoleBuilder.length - maxConsoleSize)
                    }
                    _consoleText.value = consoleBuilder.toString()

                    logOut.write(chunk, 0, n)
                    logOut.flush()

                    if (_bootStage.value != "Ready") {
                        detectBootStage(text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Boot monitor ended: ${e.message}")
        } finally {
            try { sock.close() } catch (_: Exception) {}
            bootSocket = null
            Log.d(TAG, "Boot monitor disconnected — bridge can now connect")
        }
    }

    private fun detectBootStage(text: String) {
        when {
            text.contains("Ready!") -> {
                _bootStage.value = "Ready"
                _state.value = VmState.Running
                autoStartBridge()
            }
            text.contains("Almost ready") -> _bootStage.value = "Almost ready..."
            text.contains("Starting SSH") -> _bootStage.value = "Starting SSH..."
            text.contains("Configuring containers") -> _bootStage.value = "Configuring containers..."
            text.contains("Network found") -> _bootStage.value = "Network found"
            text.contains("Loading kernel modules") -> _bootStage.value = "Loading kernel modules..."
            text.contains("Mounting storage") -> _bootStage.value = "Mounting storage..."
            text.contains("Booting kernel") -> _bootStage.value = "Booting kernel..."
        }
    }

    fun stop() {
        val proc = process
        if (proc != null) {
            proc.destroy()
            try {
                val exited = proc.waitFor(3, TimeUnit.SECONDS)
                if (!exited) {
                    proc.destroyForcibly()
                    proc.waitFor(2, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
                proc.destroyForcibly()
            }
        }
        cleanup()
        _state.value = VmState.Stopped
    }

    private fun cleanup() {
        releaseSerial()
        ioScope?.cancel()
        ioScope = null
        process = null
        _terminalSession?.finishIfRunning()
        _terminalSession = null
        _terminalSessionAttached = false
        sessionClientDelegate = null
        consoleBuilder.clear()
        _consoleText.value = ""
        File(serialSockPath).delete()
        File(terminalSockPath).delete()
        File(ctrlSockPath).delete()
        _bootStage.value = ""
    }

    private fun buildCommand(
        qemuExe: File,
        portForwards: List<PortForwardRule>,
        ramMb: Int,
        cpus: Int,
        sshEnabled: Boolean,
        androidIp: String,
    ): List<String> {
        val args = mutableListOf<String>()

        // User-tunable extras (CPU model, accel tuning, RNG, overcommit, kernel cmdline extras).
        // Pulled from SettingsRepository; falls back to the documented defaults.
        val userQemuExtras = kotlinx.coroutines.runBlocking {
            settingsRepository.getQemuExtraArgsSnapshot()
        }.trim()
        val userKernelExtras = kotlinx.coroutines.runBlocking {
            settingsRepository.getKernelExtraCmdlineSnapshot()
        }.trim()

        args += "-M"; args += "virt,gic-version=3"
        args += "-smp"; args += "$cpus"
        args += "-m";   args += "$ramMb"

        val kernelPath = File(context.filesDir, "vmlinuz-virt")
        val initrdPath = File(context.filesDir, "initrd.img")

        if (kernelPath.exists()) {
            args += "-kernel"; args += kernelPath.absolutePath
            val cmdline = buildString {
                append("console=ttyAMA0")
                if (userKernelExtras.isNotEmpty()) append(" ").append(userKernelExtras)
                append(" androidip=$androidIp")
                if (sshEnabled) append(" ssh=1")
            }
            args += "-append"; args += cmdline
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
            args += "-object"; args += "iothread,id=iothread0"
            args += "-device"; args += "virtio-blk-pci,drive=drive1,num-queues=$cpus,iothread=iothread0"
            args += "-drive";  args += "file=${storagePath.absolutePath},if=none,id=drive1,format=raw,cache=writeback,aio=threads"
        }

        // Downloads folder sharing via virtio-9p
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val storageAccessEnabled = kotlinx.coroutines.runBlocking {
            settingsRepository.getStorageAccessEnabledSnapshot()
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            storageAccessEnabled &&
            android.os.Environment.isExternalStorageManager() &&
            downloadsDir.exists()) {
            args += "-fsdev"
            args += "local,id=fsdev0,path=${downloadsDir.absolutePath},security_model=none"
            args += "-device"
            args += "virtio-9p-pci,fsdev=fsdev0,mount_tag=downloads"
        }

        val netdevArg = buildString {
            append("user,id=net0,ipv6=off")
            for (rule in portForwards) {
                append(",hostfwd=${rule.protocol}::${rule.hostPort}-:${rule.guestPort}")
            }
        }
        args += "-netdev"; args += netdevArg
        args += "-device"; args += "virtio-net-pci,netdev=net0,romfile="

        // ── Serial (ttyAMA0) → boot log sink only; kernel msgs + init boot stages ─
        args += "-serial"; args += "unix:$serialSockPath,server,nowait"

        // ── virtio-console bus ────────────────────────────────────────────────
        // hvc0 = primary terminal (getty runs here; bridge connects to terminal.sock)
        // hvc1 = control channel (init daemon reads RESIZE messages from ctrl.sock)
        args += "-device";  args += "virtio-serial-pci"
        args += "-chardev"; args += "socket,id=term0,path=$terminalSockPath,server=on,wait=off"
        args += "-device";  args += "virtconsole,chardev=term0,name=org.podroid.term"
        args += "-chardev"; args += "socket,id=ctrl0,path=$ctrlSockPath,server=on,wait=off"
        args += "-device";  args += "virtconsole,chardev=ctrl0,name=org.podroid.ctrl"

        args += "-display"; args += "none"
        args += "-qmp";     args += "unix:$qmpSocketPath,server,nowait"

        // User extras appended last so they can override earlier args where
        // QEMU allows it (later -cpu / -accel overrides earlier ones).
        if (userQemuExtras.isNotEmpty()) {
            args += userQemuExtras.split(Regex("\\s+"))
        }

        return listOf(qemuExe.absolutePath) + args
    }

    private fun ensureStorageImage() {
        val storageFile = File(context.filesDir, "storage.img")
        val gb = kotlinx.coroutines.runBlocking {
            settingsRepository.getStorageSizeGbSnapshot().toLong()
        }
        val desiredBytes = gb * 1024L * 1024L * 1024L

        if (storageFile.exists()) {
            if (storageFile.length() == desiredBytes) return
            Log.d(TAG, "storage.img size mismatch — recreating")
            storageFile.delete()
        }

        try {
            java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
            Log.d(TAG, "Created storage.img (${gb}GB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create storage.img", e)
        }
    }

    private fun qemuExecutable(): File? {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
        return if (exe.exists()) exe else null
    }

    companion object {
        private const val TAG = "PodroidQemu"
    }
}
