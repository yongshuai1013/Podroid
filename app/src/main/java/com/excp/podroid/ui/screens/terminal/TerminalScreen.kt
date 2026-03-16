/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Terminal screen using Termux TerminalView for full VT100/xterm emulation.
 */
package com.excp.podroid.ui.screens.terminal

import android.app.Activity
import android.graphics.Typeface
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.excp.podroid.engine.VmState
import com.termux.view.TerminalView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onNavigateBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val vmState by viewModel.vmState.collectAsState()

    val isVmRunning = vmState is VmState.Running

    // Keep screen on and adjust for soft keyboard
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime)
    ) {
        // Top bar
        TopAppBar(
            title = { Text("Terminal", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF1A1A1A),
            ),
        )

        // Termux TerminalView
        if (!isVmRunning) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "VM Not Running",
                        color = Color.Red,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Start the VM from Home screen first",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        setTextSize(24)
                        setTypeface(Typeface.MONOSPACE)
                        keepScreenOn = true
                        isFocusable = true
                        isFocusableInTouchMode = true

                        // Create session wired to QEMU I/O (no host binaries)
                        viewModel.createSession()
                        viewModel.attachView(this)
                        setTerminalViewClient(viewModel.viewClient)

                        // Set session and emulator directly on the view
                        // (avoid attachSession which calls JNI.setPtyWindowSize)
                        val session = viewModel.session!!
                        mTermSession = session
                        mEmulator = session.emulator

                        // Request focus so keyboard works
                        requestFocus()

                        // Resize emulator to match view after layout
                        post {
                            if (width > 0 && height > 0 && mRenderer != null) {
                                try {
                                    val rendererClass = mRenderer.javaClass
                                    val fw = rendererClass.getDeclaredField("mFontWidth").apply { isAccessible = true }.getFloat(mRenderer)
                                    val fls = rendererClass.getDeclaredField("mFontLineSpacing").apply { isAccessible = true }.getInt(mRenderer)
                                    val flsa = rendererClass.getDeclaredField("mFontLineSpacingAndAscent").apply { isAccessible = true }.getInt(mRenderer)
                                    val cols = (width / fw).toInt().coerceAtLeast(4)
                                    val rows = ((height - flsa) / fls).coerceAtLeast(4)
                                    session.emulator?.resize(cols, rows)
                                    onScreenUpdated()
                                } catch (_: Exception) {}
                            }
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }

        // Extra keys row
        ExtraKeysRow(
            onKey = { viewModel.sendExtraKey(it) },
            ctrlActive = viewModel.extraCtrl,
            altActive = viewModel.extraAlt,
        )
    }
}

@Composable
private fun ExtraKeysRow(
    onKey: (String) -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtraKey("ESC", onKey)
        ExtraKey("TAB", onKey)
        ExtraKey("CTRL", onKey, active = ctrlActive)
        ExtraKey("\u2190", onKey, sendKey = "LEFT")     // ←
        ExtraKey("\u2191", onKey, sendKey = "UP")       // ↑
        ExtraKey("\u2193", onKey, sendKey = "DOWN")     // ↓
        ExtraKey("\u2192", onKey, sendKey = "RIGHT")    // →
        ExtraKey("ALT", onKey, active = altActive)
        ExtraKey("-", onKey)
        ExtraKey("/", onKey)
        ExtraKey("|", onKey)
        ExtraKey("HOME", onKey)
        ExtraKey("END", onKey)
        ExtraKey("PGUP", onKey)
        ExtraKey("PGDN", onKey)
    }
}

@Composable
private fun ExtraKey(
    label: String,
    onKey: (String) -> Unit,
    sendKey: String = label,
    active: Boolean = false,
) {
    Text(
        text = label,
        color = if (active) Color.Black else Color(0xFFCCCCCC),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) Color(0xFF4FC3F7) else Color(0xFF333333))
            .clickable { onKey(sendKey) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
