package com.excp.podroid.ui.screens.setup

import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.data.repository.dataStore
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _setupComplete = MutableStateFlow(false)
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    /**
     * Persists all setup choices in a single DataStore transaction so a process
     * kill mid-write can't leave the app in a half-completed setup state.
     */
    fun completeSetup(storageSizeGb: Int, sshEnabled: Boolean, storageAccessEnabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[SettingsRepository.KEY_STORAGE_GB] = storageSizeGb
                prefs[SettingsRepository.KEY_SSH_ENABLED] = sshEnabled
                prefs[SettingsRepository.KEY_STORAGE_ACCESS_ENABLED] = storageAccessEnabled
                prefs[SettingsRepository.KEY_SETUP_DONE] = true
            }
            _setupComplete.value = true
        }
    }
}
