package com.excp.podroid.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.BuildConfig
import com.excp.podroid.engine.VmState
import com.excp.podroid.ui.components.AdaptiveContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateToTerminal: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val bootStage by viewModel.bootStage.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()

    val isRunning = vmState is VmState.Running
    val isStarting = vmState is VmState.Starting

    // Update dialog
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdate() },
            icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
            title = { Text("Update available") },
            text = {
                Text("Version ${info.latestVersion} is available. You have ${BuildConfig.VERSION_NAME}.")
            },
            confirmButton = {
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                    viewModel.dismissUpdate()
                }) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdate() }) {
                    Text("Later")
                }
            },
        )
    }

    val isCompactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Podroid") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            maxWidth = if (isCompactHeight) 900 else 600,
        ) {
            if (isCompactHeight) {
                // Landscape phone: hero on left, action buttons on right
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        StatusHero(isStarting = isStarting, isRunning = isRunning, bootStage = bootStage, iconSize = 64)
                    }
                    Spacer(Modifier.width(24.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HomeActionButtons(
                            vmState = vmState,
                            isRunning = isRunning,
                            isStarting = isStarting,
                            onStart = { viewModel.startPodroid() },
                            onStop = { viewModel.stopVm() },
                            onRestart = { viewModel.restartVm() },
                            onOpenTerminal = onNavigateToTerminal,
                        )
                    }
                }
            } else {
                // Portrait / tall: vertical stack
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    StatusHero(isStarting = isStarting, isRunning = isRunning, bootStage = bootStage, iconSize = 72)
                    HomeActionButtons(
                        vmState = vmState,
                        isRunning = isRunning,
                        isStarting = isStarting,
                        onStart = { viewModel.startPodroid() },
                        onStop = { viewModel.stopVm() },
                        onRestart = { viewModel.restartVm() },
                        onOpenTerminal = onNavigateToTerminal,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Alpine Linux · Podman · QEMU",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusHero(
    isStarting: Boolean,
    isRunning: Boolean,
    bootStage: String,
    iconSize: Int,
) {
    Box(modifier = Modifier.size(iconSize.dp), contentAlignment = Alignment.Center) {
        if (isStarting) {
            com.excp.podroid.ui.screens.home.AnimatedBootProgress(
                bootStage = bootStage,
                modifier = Modifier.size(iconSize.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(iconSize.dp),
                tint = if (isRunning)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when {
                isStarting -> "Starting…"
                isRunning  -> "VM is running"
                else       -> "VM is stopped"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = when {
                isStarting -> MaterialTheme.colorScheme.primary
                isRunning  -> MaterialTheme.colorScheme.primary
                else       -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (isStarting && bootStage.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = bootStage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HomeActionButtons(
    vmState: VmState,
    isRunning: Boolean,
    isStarting: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Button(
        onClick = { if (isRunning || isStarting) onStop() else onStart() },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning || isStarting)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (isRunning || isStarting) "Stop VM" else "Start VM",
            style = MaterialTheme.typography.titleMedium,
        )
    }
    if (isRunning) {
        FilledTonalButton(
            onClick = onRestart,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Restart VM")
        }
    }
    if (vmState is VmState.Error) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = vmState.message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }
        }
    }
    if (isRunning) {
        FilledTonalButton(
            onClick = onOpenTerminal,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Open Terminal",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
