/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * QEMU configuration for Podroid (Podman on Android).
 * Uses initrd approach with persistent overlay for Alpine Linux VM.
 */
package com.excp.podroid.engine

import android.content.Context
import android.util.Log
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.PortForwardRule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodroidQemu @Inject constructor(
    @ApplicationContext private val context: Context,
    private val portForwardRepository: PortForwardRepository,
) {
    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    val state: StateFlow<VmState> = _state.asStateFlow()

    /** Accumulated serial console output (for fallback / logging) */
    private val _consoleText = MutableStateFlow("")
    val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    var process: Process? = null
        private set

    /** Stream to write to VM serial console (QEMU stdin) */
    var consoleOutput: OutputStream? = null
        private set

    /**
     * Callback for raw console output bytes. When set, each chunk read from
     * QEMU stdout is forwarded here (used by QemuTerminalSession).
     */
    var onConsoleOutput: ((ByteArray, Int) -> Unit)? = null

    /** QMP client for runtime VM management */
    val qmpClient: QmpClient by lazy {
        QmpClient("${context.filesDir.absolutePath}/qmp.sock")
    }

    fun start() {
        start(emptyList())
    }

    fun start(portForwards: List<PortForwardRule>) {
        if (_state.value is VmState.Starting || _state.value is VmState.Running) {
            Log.w(TAG, "start() called while VM is in state ${_state.value}, ignoring")
            return
        }

        val qemuExe = qemuExecutable() ?: run {
            _state.value = VmState.Error("QEMU binary not found.")
            return
        }

        _state.value = VmState.Starting
        _consoleText.value = ""

        try {
            val cmd = buildCommand(qemuExe, portForwards)
            Log.d(TAG, "Launching: ${cmd.joinToString(" ")}")

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val filesDir = context.filesDir.absolutePath
            Log.d(TAG, "nativeDir: $nativeDir, filesDir: $filesDir")

            val pb = ProcessBuilder(cmd)
                .directory(context.filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeDir:$filesDir"

            process = pb.start()
            consoleOutput = process!!.outputStream
            Log.d(TAG, "Process started")

            // Read serial console output (stdout), log to file, and forward to listeners
            val logFile = File(context.filesDir, "console.log")
            logFile.delete()
            Thread({
                try {
                    val buf = ByteArray(4096)
                    val input = process!!.inputStream
                    val logOut = FileOutputStream(logFile, true)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val chunk = buf.copyOf(n)
                        _consoleText.value += String(chunk)
                        logOut.write(chunk, 0, n)
                        logOut.flush()
                        // Forward to terminal session if attached
                        onConsoleOutput?.invoke(chunk, n)
                    }
                    logOut.close()
                } catch (e: Exception) {
                    Log.d(TAG, "Console reader ended: ${e.message}")
                }
            }, "qemu-console-reader").apply {
                isDaemon = true
                start()
            }

            // Drain stderr separately
            Thread({
                try {
                    val buf = ByteArray(4096)
                    val err = process!!.errorStream
                    while (true) {
                        val n = err.read(buf)
                        if (n < 0) break
                        Log.d("PodroidVM-err", String(buf, 0, n).trimEnd().take(300))
                    }
                } catch (_: Exception) {}
            }, "qemu-stderr-drain").apply {
                isDaemon = true
                start()
            }

            // After a short delay, mark as running if process is still alive
            Thread.sleep(2000)
            if (process?.isAlive == true) {
                Log.d(TAG, "QEMU is running!")
                _state.value = VmState.Running
            } else {
                Log.e(TAG, "QEMU died immediately")
                val exitCode = process?.waitFor() ?: -1
                Log.e(TAG, "Exit code: $exitCode")
                _state.value = VmState.Error("QEMU exited with code $exitCode")
                return
            }

            // Wait for process to exit
            val exitCode = process?.waitFor() ?: -1
            Log.d(TAG, "QEMU exited with code: $exitCode")
            process = null
            consoleOutput = null

            _state.value = if (exitCode == 0) VmState.Stopped else VmState.Error("Exit code $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QEMU", e)
            _state.value = VmState.Error(e.message ?: "Unknown error")
            process = null
            consoleOutput = null
        }
    }

    fun stop() {
        // Send sync via serial console for graceful shutdown
        try {
            consoleOutput?.let { out ->
                out.write("sync\n".toByteArray())
                out.flush()
                Thread.sleep(500)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to send sync: ${e.message}")
        }

        process?.destroy()
        try { Thread.sleep(1000) } catch (_: Exception) {}
        if (process?.isAlive == true) {
            process?.destroyForcibly()
        }
        process = null
        consoleOutput = null
        onConsoleOutput = null
        _state.value = VmState.Stopped
    }

    private fun buildCommand(qemuExe: File, portForwards: List<PortForwardRule>): List<String> {
        val args = mutableListOf<String>()

        // Machine & CPU
        args += "-M"; args += "virt"
        args += "-cpu"; args += "max"
        args += "-m"; args += "1024"
        args += "-accel"; args += "tcg,thread=multi"

        // Kernel & Initrd (Podroid Core)
        val kernelPath = File(context.filesDir, "vmlinuz-virt")
        val initrdPath = File(context.filesDir, "initrd.img")
        Log.d(TAG, "kernelPath: $kernelPath, exists: ${kernelPath.exists()}")
        Log.d(TAG, "initrdPath: $initrdPath, exists: ${initrdPath.exists()}")

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

        // Storage (Persistent)
        val storagePath = File(context.filesDir, "storage.img")
        if (storagePath.exists()) {
            args += "-device"; args += "virtio-blk-pci,drive=drive1"
            args += "-drive"; args += "file=${storagePath.absolutePath},if=none,id=drive1,format=raw"
        }

        // Network with port forwarding
        val netdevArg = buildString {
            append("user,id=net0")
            for (rule in portForwards) {
                append(",hostfwd=${rule.protocol}::${rule.hostPort}-:${rule.guestPort}")
            }
        }
        args += "-netdev"; args += netdevArg
        args += "-device"; args += "virtio-net,netdev=net0"

        // Serial console via stdio — bidirectional through process stdin/stdout
        args += "-serial"; args += "stdio"

        // Display (headless)
        args += "-display"; args += "none"

        // QMP socket for management (port forwarding, VM control)
        args += "-qmp"
        args += "unix:${context.filesDir.absolutePath}/qmp.sock,server,nowait"

        // Misc
        args += "-overcommit"; args += "mem-lock=off"

        return listOf(qemuExe.absolutePath) + args
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
