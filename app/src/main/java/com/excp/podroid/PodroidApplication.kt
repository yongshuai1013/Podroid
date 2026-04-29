/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Application class — extracts QEMU, kernel, and initrd assets on first run
 * (and on app upgrade when an asset's size changes).
 */
package com.excp.podroid

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class PodroidApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        extractAssets()
    }

    private fun extractAssets() {
        copyAssetDir("qemu", filesDir)
        copyAssetIfNeeded("vmlinuz-virt", filesDir)
        copyAssetIfNeeded("initrd.img", filesDir)
        copyAssetIfNeeded("alpine-rootfs.squashfs", filesDir)
    }

    /**
     * Copies an asset to destDir if missing or size-different.
     * Uses openFd() for an O(1) size lookup; that throws for compressed
     * assets, in which case we fall back to always copying.
     */
    private fun copyAssetIfNeeded(assetName: String, destDir: File) {
        val destFile = File(destDir, assetName)
        try {
            val assetSize = try { assets.openFd(assetName).use { it.length } } catch (_: Exception) { -1L }
            if (assetSize >= 0 && destFile.exists() && destFile.length() == assetSize) return

            assets.open(assetName).use { input ->
                destFile.parentFile?.mkdirs()
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract $assetName", e)
        }
    }

    /**
     * Walks an asset directory tree and mirrors it under destDir.
     * Each file is copied if missing OR if its size differs (handles app
     * upgrades that ship modified BIOS/keymap files — pre-1.1.6 this used
     * !exists() and silently kept stale copies).
     */
    private fun copyAssetDir(assetPath: String, destDir: File) {
        val entries = assets.list(assetPath) ?: return
        for (entry in entries) {
            val src = "$assetPath/$entry"
            val dest = File(destDir, entry)
            val subEntries = assets.list(src)
            if (subEntries != null && subEntries.isNotEmpty()) {
                dest.mkdirs()
                copyAssetDir(src, dest)
            } else {
                copyAssetFileIfNeeded(src, dest)
            }
        }
    }

    private fun copyAssetFileIfNeeded(assetPath: String, destFile: File) {
        try {
            val assetSize = try { assets.openFd(assetPath).use { it.length } } catch (_: Exception) { -1L }
            if (assetSize >= 0 && destFile.exists() && destFile.length() == assetSize) return

            assets.open(assetPath).use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract $assetPath", e)
        }
    }

    companion object {
        private const val TAG = "PodroidApp"
    }
}
