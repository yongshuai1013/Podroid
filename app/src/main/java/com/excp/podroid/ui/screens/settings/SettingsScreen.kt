package com.excp.podroid.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.BuildConfig
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.engine.VmState
import com.excp.podroid.ui.components.AdaptiveContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onThemeOrFontChanged: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle(false)
    val portForwardRules by viewModel.portForwardRules.collectAsStateWithLifecycle()
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val vmRamMb by viewModel.vmRamMb.collectAsStateWithLifecycle()
    val vmCpus by viewModel.vmCpus.collectAsStateWithLifecycle()
    val storageSizeGb by viewModel.storageSizeGb.collectAsStateWithLifecycle()
    val sshEnabled by viewModel.sshEnabled.collectAsStateWithLifecycle(false)
    val qemuExtraArgs by viewModel.qemuExtraArgs.collectAsStateWithLifecycle()
    val kernelExtraCmdline by viewModel.kernelExtraCmdline.collectAsStateWithLifecycle()
    var advancedExpanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val ramOptions = listOf(512, 1024, 2048, 4096)
    val cpuOptions = listOf(1, 2, 4, 6, 8)
    val vmNotRunning = vmState !is VmState.Running && vmState !is VmState.Starting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
            Spacer(Modifier.height(8.dp))

            // ── VM Resources ─────────────────────────────────────────
            SettingsSectionHeader("VM Resources")

            if (!vmNotRunning) {
                Text(
                    text = "Stop the VM to change these settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Text(
                text = "Memory: $vmRamMb MB",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ramOptions.forEach { ram ->
                    FilledTonalButton(
                        onClick = { viewModel.setVmRamMb(ram) },
                        enabled = vmNotRunning,
                        modifier = Modifier.weight(1f),
                        colors = if (vmRamMb == ram) {
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                    ) {
                        Text(
                            if (ram >= 1024) "${ram / 1024}G" else "${ram}M",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "CPU cores: $vmCpus",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cpuOptions.forEach { cpu ->
                    FilledTonalButton(
                        onClick = { viewModel.setVmCpus(cpu) },
                        enabled = vmNotRunning,
                        modifier = Modifier.weight(1f),
                        colors = if (vmCpus == cpu) {
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                    ) {
                        Text("$cpu", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))



            Spacer(Modifier.height(4.dp))

            // Storage size (read-only — set at first boot)
            Text(
                text = "Persistent storage: $storageSizeGb GB",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Set during initial setup. Reset the VM to change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            Spacer(Modifier.height(16.dp))

            // ── Network ───────────────────────────────────────────────
            SettingsSectionHeader("Network")

            val phoneIp = viewModel.phoneIp
            Text(
                text = "Phone IP: $phoneIp — point your devices here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            SettingsSwitchRow(
                title = "Enable SSH",
                subtitle = if (sshEnabled) "ssh root@<phone-ip> -p 9922  |  password: podroid" else "Access the VM over your local network via SSH",
                checked = sshEnabled,
                onCheckedChange = { viewModel.setSshEnabled(it) },
            )
            Text(
                text = "Rules forward ports from your Android device into the VM. Active immediately when VM is running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (portForwardRules.isEmpty()) {
                Text(
                    text = "No rules configured.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                portForwardRules.forEach { rule ->
                    PortForwardRuleRow(
                        rule = rule,
                        onDelete = { viewModel.removePortForward(rule) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            FilledTonalButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Port Forward")
            }

            if (vmState is VmState.Running) {
                Text(
                    text = "VM is running — new rules take effect immediately.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingsSectionHeader("Appearance")
            SettingsSwitchRow(
                title = "Dark theme",
                subtitle = "Use a dark color scheme",
                checked = darkTheme,
                onCheckedChange = { viewModel.setDarkTheme(it) },
            )
            Spacer(Modifier.height(16.dp))

            SettingsSectionHeader("Diagnostics")

            FilledTonalButton(
                onClick = { viewModel.exportConsoleLogs() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Export Diagnostic Log")
            }
            Text(
                text = "Shares log.txt with app info, settings, VM state, app logcat, and QEMU console output. Attach this when reporting bugs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(8.dp))

            FilledTonalButton(
                onClick = { showResetDialog = true },
                enabled = vmNotRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Full App Reset")
            }

            if (!vmNotRunning) {
                Text(
                    text = "Stop the VM before resetting.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Advanced QEMU ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Advanced QEMU",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (advancedExpanded) "Collapse" else "Expand",
                )
            }

            if (advancedExpanded) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                        )
                        Text(
                            text = "Editing these may prevent the VM from booting. " +
                                "RAM, CPU count, sockets, storage, and networking are " +
                                "managed by the app and not exposed here. Use Reset if " +
                                "the VM stops booting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                if (!vmNotRunning) {
                    Text(
                        text = "Stop the VM before editing — changes apply on next boot.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                AdvancedTextSetting(
                    label = "Extra QEMU args",
                    helper = "-cpu, -accel, -object, -device, -overcommit, etc. Whitespace-separated.",
                    value = qemuExtraArgs,
                    enabled = vmNotRunning,
                    onValueChange = viewModel::setQemuExtraArgs,
                    onReset = viewModel::resetQemuExtraArgs,
                    minLines = 4,
                )

                Spacer(Modifier.height(12.dp))

                AdvancedTextSetting(
                    label = "Extra kernel cmdline",
                    helper = "Appended after console=ttyAMA0 — controls scheduler, mitigations, log level, etc.",
                    value = kernelExtraCmdline,
                    enabled = vmNotRunning,
                    onValueChange = viewModel::setKernelExtraCmdline,
                    onReset = viewModel::resetKernelExtraCmdline,
                    minLines = 2,
                )

                Spacer(Modifier.height(16.dp))
            }

            // ── About ─────────────────────────────────────────────────
            SettingsSectionHeader("About")

            SettingsInfoRow("Version", BuildConfig.VERSION_NAME)
            SettingsInfoRow("QEMU", BuildConfig.QEMU_VERSION)
            SettingsInfoRow("Architecture", "AArch64 (ARM64)")
            SettingsInfoRow("Linux distro", "Alpine Linux 3.23")
            SettingsInfoRow("Container runtime", "Podman + crun")
            SettingsInfoRow("Storage", "$storageSizeGb GB persistent overlay")

            Spacer(Modifier.height(32.dp))
        }
    }
}

    if (showAddDialog) {
        AddPortForwardDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { hostPort, guestPort, protocol ->
                viewModel.addPortForward(hostPort, guestPort, protocol)
                showAddDialog = false
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Full App Reset?") },
            text = {
                Text(
                    "This will clear ALL application data, including your VM storage, " +
                    "settings, and port rules. The app will close and return to a " +
                    "freshly-installed state."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetVm()
                    showResetDialog = false
                }) {
                    Text("Reset Everything", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun PortForwardRuleRow(rule: PortForwardRule, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "localhost:${rule.hostPort}  →  VM:${rule.guestPort}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = rule.protocol.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove rule",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddPortForwardDialog(
    onDismiss: () -> Unit,
    onAdd: (hostPort: Int, guestPort: Int, protocol: String) -> Unit,
) {
    var hostPort by remember { mutableStateOf("") }
    var guestPort by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("tcp") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add port forward") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hostPort,
                    onValueChange = { hostPort = it; error = null },
                    label = { Text("Android port") },
                    placeholder = { Text("e.g. 8080") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = guestPort,
                    onValueChange = { guestPort = it; error = null },
                    label = { Text("VM port") },
                    placeholder = { Text("e.g. 80") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("tcp", "udp", "both").forEach { proto ->
                        FilterChip(
                            selected = protocol == proto,
                            onClick = { protocol = proto },
                            label = { Text(proto.uppercase(), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hp = hostPort.toIntOrNull()
                val gp = guestPort.toIntOrNull()
                if (hp == null || gp == null || hp !in 1..65535 || gp !in 1..65535) {
                    error = "Enter valid port numbers (1–65535)"
                    return@TextButton
                }
                onAdd(hp, gp, protocol)
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AdvancedTextSetting(
    label: String,
    helper: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
    minLines: Int,
) {
    var localValue by remember(value) { mutableStateOf(value) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = localValue,
            onValueChange = {
                localValue = it
                onValueChange(it)
            },
            label = { Text(label) },
            enabled = enabled,
            singleLine = false,
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onReset,
                enabled = enabled,
            ) {
                Text("Reset")
            }
        }
    }
}
