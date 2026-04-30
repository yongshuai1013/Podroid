/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Persists port forwarding rules in DataStore.
 */
package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single port forwarding rule.
 * @param hostPort Port on the Android device
 * @param guestPort Port inside the VM
 * @param protocol "tcp" or "udp"
 */
data class PortForwardRule(
    val hostPort: Int,
    val guestPort: Int,
    val protocol: String = "tcp",
) {
    fun serialize(): String = "$protocol:$hostPort:$guestPort"

    companion object {
        fun deserialize(s: String): PortForwardRule? {
            val parts = s.split(":")
            if (parts.size != 3) return null
            val proto = parts[0]
            val host = parts[1].toIntOrNull() ?: return null
            val guest = parts[2].toIntOrNull() ?: return null
            return PortForwardRule(host, guest, proto)
        }
    }
}

@Singleton
class PortForwardRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val KEY_PORT_FORWARDS = stringSetPreferencesKey("port_forwards")
    }

    val rules: Flow<List<PortForwardRule>> = context.dataStore.data
        .map { prefs ->
            prefs[KEY_PORT_FORWARDS]
                ?.mapNotNull { PortForwardRule.deserialize(it) }
                ?: emptyList()
        }

    suspend fun addRule(rule: PortForwardRule) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PORT_FORWARDS]?.toMutableSet() ?: mutableSetOf()
            current.add(rule.serialize())
            prefs[KEY_PORT_FORWARDS] = current
        }
    }

    suspend fun removeRule(rule: PortForwardRule) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PORT_FORWARDS]?.toMutableSet() ?: return@edit
            current.remove(rule.serialize())
            prefs[KEY_PORT_FORWARDS] = current
        }
    }

    suspend fun getRulesSnapshot(): List<PortForwardRule> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_PORT_FORWARDS]
                ?.mapNotNull { PortForwardRule.deserialize(it) }
                ?: emptyList()
        }.first()
}
