/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Minimal QMP (QEMU Machine Protocol) client for runtime VM management.
 * Used for adding/removing port forwards while the VM is running.
 */
package com.excp.podroid.engine

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class QmpClient(private val socketPath: String) {

    companion object {
        private const val TAG = "QmpClient"
    }

    /**
     * Execute a QMP command and return the response.
     * Each call opens a new connection (QMP is stateless per-connection after capabilities).
     */
    suspend fun execute(command: String, arguments: JSONObject? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                val socket = LocalSocket()
                socket.connect(
                    LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
                )

                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val writer = OutputStreamWriter(socket.outputStream)

                // Read QMP greeting
                val greeting = reader.readLine()
                Log.d(TAG, "QMP greeting: $greeting")

                // Send qmp_capabilities to enter command mode
                writer.write("{\"execute\":\"qmp_capabilities\"}\n")
                writer.flush()
                val capResponse = reader.readLine()
                Log.d(TAG, "Capabilities response: $capResponse")

                // Send the actual command
                val cmd = JSONObject().apply {
                    put("execute", command)
                    if (arguments != null) put("arguments", arguments)
                }
                writer.write(cmd.toString() + "\n")
                writer.flush()

                val response = reader.readLine()
                Log.d(TAG, "Command response: $response")

                socket.close()
                Result.success(JSONObject(response ?: "{}"))
            } catch (e: Exception) {
                Log.e(TAG, "QMP command failed: $command", e)
                Result.failure(e)
            }
        }

    /**
     * Add a port forward rule at runtime via QEMU monitor.
     * @param hostPort Port on the Android host
     * @param guestPort Port inside the VM
     * @param protocol "tcp" or "udp"
     */
    suspend fun addPortForward(hostPort: Int, guestPort: Int, protocol: String = "tcp"): Result<JSONObject> {
        val monitorCmd = "hostfwd_add net0 ${protocol}::${hostPort}-:${guestPort}"
        return execute(
            "human-monitor-command",
            JSONObject().put("command-line", monitorCmd)
        )
    }

    /**
     * Remove a port forward rule at runtime via QEMU monitor.
     */
    suspend fun removePortForward(hostPort: Int, protocol: String = "tcp"): Result<JSONObject> {
        val monitorCmd = "hostfwd_remove net0 ${protocol}::${hostPort}"
        return execute(
            "human-monitor-command",
            JSONObject().put("command-line", monitorCmd)
        )
    }
}
