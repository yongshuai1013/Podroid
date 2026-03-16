/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Settings ViewModel for Podroid.
 */
package com.excp.podroid.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val podroidQemu: PodroidQemu,
) : ViewModel() {

    val darkTheme: Flow<Boolean> = settingsRepository.darkTheme

    val portForwardRules: StateFlow<List<PortForwardRule>> = portForwardRepository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vmState: StateFlow<VmState> = podroidQemu.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VmState.Idle)

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkTheme(value) }
    }

    fun addPortForward(hostPort: Int, guestPort: Int, protocol: String = "tcp") {
        val rule = PortForwardRule(hostPort, guestPort, protocol)
        viewModelScope.launch {
            portForwardRepository.addRule(rule)
            // If VM is running, apply immediately via QMP
            if (podroidQemu.state.value is VmState.Running) {
                podroidQemu.qmpClient.addPortForward(hostPort, guestPort, protocol)
            }
        }
    }

    fun removePortForward(rule: PortForwardRule) {
        viewModelScope.launch {
            portForwardRepository.removeRule(rule)
            // If VM is running, remove immediately via QMP
            if (podroidQemu.state.value is VmState.Running) {
                podroidQemu.qmpClient.removePortForward(rule.hostPort, rule.protocol)
            }
        }
    }
}
