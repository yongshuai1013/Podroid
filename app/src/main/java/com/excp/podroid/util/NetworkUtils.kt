/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Tiny shared helper for resolving the device's primary IPv4 address.
 * Used by both PodroidService (when launching QEMU) and the Settings UI
 * (to display "Phone IP: …" next to port-forward rules).
 */
package com.excp.podroid.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** First non-loopback IPv4 address, or "unknown" if none / on error. */
    fun localIpv4(): String = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "unknown"
    } catch (_: Exception) { "unknown" }
}
