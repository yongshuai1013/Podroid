/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * App-wide settings backed by DataStore.
 */
package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        val KEY_DARK_THEME             = booleanPreferencesKey("dark_theme")
        val KEY_VM_RAM                 = intPreferencesKey("vm_ram_mb")
        val KEY_VM_CPUS                = intPreferencesKey("vm_cpus")
        val KEY_FONT_SIZE              = intPreferencesKey("terminal_font_size")
        val KEY_STORAGE_GB             = intPreferencesKey("storage_gb")
        val KEY_STORAGE_ACCESS_ENABLED = booleanPreferencesKey("storage_access_enabled")
        val KEY_SETUP_DONE             = booleanPreferencesKey("setup_done")
        val KEY_SSH_ENABLED            = booleanPreferencesKey("ssh_enabled")
        val KEY_TERMINAL_COLOR_THEME   = stringPreferencesKey("terminal_color_theme")
        val KEY_TERMINAL_FONT          = stringPreferencesKey("terminal_font")
        val KEY_QEMU_EXTRA_ARGS        = stringPreferencesKey("qemu_extra_args")
        val KEY_KERNEL_EXTRA_CMDLINE   = stringPreferencesKey("kernel_extra_cmdline")
        val KEY_SHOW_EXTRA_KEYS        = booleanPreferencesKey("show_extra_keys")
        val KEY_HAPTICS_ENABLED        = booleanPreferencesKey("haptics_enabled")

        /** Default tunable QEMU args — CPU model, accel tuning, RNG source, overcommit. */
        const val DEFAULT_QEMU_EXTRA_ARGS =
            "-cpu max " +
            "-accel tcg,thread=multi,tb-size=256 " +
            "-object rng-random,id=rng0,filename=/dev/urandom " +
            "-device virtio-rng-pci,rng=rng0 " +
            "-overcommit mem-lock=off"

        /** Default extra kernel cmdline — log level, TCG-safe mitigations, I/O scheduler. */
        const val DEFAULT_KERNEL_EXTRA_CMDLINE = "loglevel=1 quiet mitigations=off elevator=mq-deadline"
    }

    private fun <T> pref(key: Preferences.Key<T>, default: T): Flow<T> =
        context.dataStore.data.map { it[key] ?: default }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) =
        context.dataStore.edit { it[key] = value }

    val darkTheme            = pref(KEY_DARK_THEME, true)
    val vmRamMb              = pref(KEY_VM_RAM, 512)
    val vmCpus               = pref(KEY_VM_CPUS, 1)
    val terminalFontSize     = pref(KEY_FONT_SIZE, 20)
    val storageSizeGb        = pref(KEY_STORAGE_GB, 2)
    val storageAccessEnabled = pref(KEY_STORAGE_ACCESS_ENABLED, false)
    val isSetupDone          = pref(KEY_SETUP_DONE, false)
    val sshEnabled           = pref(KEY_SSH_ENABLED, false)
    val terminalColorTheme   = pref(KEY_TERMINAL_COLOR_THEME, "default")
    val terminalFont         = pref(KEY_TERMINAL_FONT, "default")
    val qemuExtraArgs        = pref(KEY_QEMU_EXTRA_ARGS, DEFAULT_QEMU_EXTRA_ARGS)
    val kernelExtraCmdline   = pref(KEY_KERNEL_EXTRA_CMDLINE, DEFAULT_KERNEL_EXTRA_CMDLINE)
    val showExtraKeys        = pref(KEY_SHOW_EXTRA_KEYS, true)
    val hapticsEnabled       = pref(KEY_HAPTICS_ENABLED, true)

    suspend fun setDarkTheme(value: Boolean)             = set(KEY_DARK_THEME, value)
    suspend fun setVmRamMb(value: Int)                   = set(KEY_VM_RAM, value)
    suspend fun setVmCpus(value: Int)                    = set(KEY_VM_CPUS, value)
    suspend fun setTerminalFontSize(value: Int)          = set(KEY_FONT_SIZE, value)
    suspend fun setStorageSizeGb(value: Int)             = set(KEY_STORAGE_GB, value)
    suspend fun setStorageAccessEnabled(value: Boolean)  = set(KEY_STORAGE_ACCESS_ENABLED, value)
    suspend fun markSetupDone()                          = set(KEY_SETUP_DONE, true)
    suspend fun setSshEnabled(value: Boolean)            = set(KEY_SSH_ENABLED, value)
    suspend fun setTerminalColorTheme(value: String)     = set(KEY_TERMINAL_COLOR_THEME, value)
    suspend fun setTerminalFont(value: String)           = set(KEY_TERMINAL_FONT, value)
    suspend fun setQemuExtraArgs(value: String)          = set(KEY_QEMU_EXTRA_ARGS, value)
    suspend fun setKernelExtraCmdline(value: String)     = set(KEY_KERNEL_EXTRA_CMDLINE, value)
    suspend fun setShowExtraKeys(value: Boolean)         = set(KEY_SHOW_EXTRA_KEYS, value)
    suspend fun setHapticsEnabled(value: Boolean)        = set(KEY_HAPTICS_ENABLED, value)

    // Snapshots used by non-Compose call sites (PodroidService, exporters).
    suspend fun getSshEnabledSnapshot()           = sshEnabled.first()
    suspend fun getVmRamMbSnapshot()              = vmRamMb.first()
    suspend fun getVmCpusSnapshot()               = vmCpus.first()
    suspend fun getStorageSizeGbSnapshot()        = storageSizeGb.first()
    suspend fun getStorageAccessEnabledSnapshot() = storageAccessEnabled.first()
    suspend fun isSetupDoneSnapshot()             = isSetupDone.first()
    suspend fun getTerminalColorThemeSnapshot()   = terminalColorTheme.first()
    suspend fun getTerminalFontSnapshot()         = terminalFont.first()
    suspend fun getQemuExtraArgsSnapshot()        = qemuExtraArgs.first()
    suspend fun getKernelExtraCmdlineSnapshot()   = kernelExtraCmdline.first()
}
