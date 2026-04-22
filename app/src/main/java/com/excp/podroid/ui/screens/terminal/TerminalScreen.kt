package com.excp.podroid.ui.screens.terminal

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
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
import com.excp.podroid.ui.components.AdaptiveContainer
import com.termux.view.TerminalView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val vmState by viewModel.vmState.collectAsState()
    val fontSize by viewModel.terminalFontSize.collectAsState()
    val showQuickSettings by viewModel.showQuickSettings.collectAsState()

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            @Suppress("DEPRECATION")
            activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED)
        }
    }

    // Forward app-level focus into the VM as xterm focus events (CSI I / CSI O)
    // so nvim's FocusGained/FocusLost autocommands fire. Gated by the emulator's
    // DECSET 1004 mode inside the ViewModel so we never leak literal bytes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.sendFocusEvent(true)
                Lifecycle.Event.ON_PAUSE  -> viewModel.sendFocusEvent(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val colorTheme by viewModel.terminalColorTheme.collectAsState()
    val terminalFont by viewModel.terminalFont.collectAsState()

    if (showQuickSettings) {
        QuickSettingsDialog(
            fontSize = fontSize,
            onFontSizeChange = { viewModel.setTerminalFontSize(it) },
            onDismiss = { viewModel.closeQuickSettings() },
            showExtraKeys = viewModel.showExtraKeys,
            onToggleExtraKeys = { viewModel.updateShowExtraKeys(it) },
            hapticsEnabled = viewModel.hapticsEnabled,
            onToggleHaptics = { viewModel.updateHapticsEnabled(it) },
            colorTheme = colorTheme,
            onColorThemeChange = { viewModel.setTerminalColorTheme(it) },
            terminalFont = terminalFont,
            onFontChange = { viewModel.setTerminalFont(it) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime)
    ) {
        TopAppBar(
            title = { Text("Terminal", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = {
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    (context as? Activity)?.currentFocus?.let {
                        imm.hideSoftInputFromWindow(it.windowToken, 0)
                    }
                    onNavigateBack()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
            },
            actions = {
                IconButton(onClick = { viewModel.openQuickSettings() }) {
                    Text("⚙", color = Color.White, fontSize = 18.sp)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A)),
        )

        when (vmState) {
            is VmState.Idle, is VmState.Stopped -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("VM Not Running", color = Color.Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Start the VM from Home screen first",
                            color = Color.Gray, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            is VmState.Error -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Error", color = Color.Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            (vmState as VmState.Error).message,
                            color = Color.Gray, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Tap to Retry",
                            color = Color(0xFF4FC3F7), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF333333))
                                .clickable { onNavigateBack() }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            is VmState.Starting, is VmState.Paused, is VmState.Saving, is VmState.Resuming -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF4FC3F7), strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            when (vmState) {
                                is VmState.Paused   -> "VM Paused"
                                is VmState.Saving   -> "Saving state..."
                                is VmState.Resuming -> "Resuming..."
                                else                -> ""
                            },
                            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            is VmState.Running -> {
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    val view = viewModel.getOrCreateTerminalView()

                    DisposableEffect(vmState) {
                        viewModel.resetOnRestart()
                        viewModel.attachView(view)
                        viewModel.createSession()
                        view.setTerminalViewClient(viewModel.viewClient)
                        val sess = viewModel.session
                        if (sess != null) {
                            view.mTermSession = sess
                            view.mEmulator = sess.emulator
                        }
                        view.requestFocus()

                        // Session may have buffered MOTD + prompt bytes before the view
                        // attached (the proxy SessionClient's onTextChanged no-ops when
                        // the delegate is null). Force a repaint so the emulator's
                        // existing state draws on first layout.
                        view.onScreenUpdated()

                        // Push cols/rows to the session on a schedule:
                        //   forceUpdateSizeFromView  — Paint-based fallback, works before mRenderer
                        //                             is initialized; uses currentTypeface so custom
                        //                             fonts produce correct metrics.
                        //   view.updateSize()        — renderer-based, accurate after first draw.
                        // Both are called; the renderer path is called last so it wins once ready.
                        // Multiple delays ensure the VM's ctrl.sock resize daemon receives the
                        // message even if it wasn't listening yet at the initial post.
                        val pushSize = {
                            viewModel.forceUpdateSizeFromView(view)
                            view.updateSize()
                            view.onScreenUpdated()
                        }
                        view.post(pushSize)
                        view.postDelayed(pushSize, 150)
                        view.postDelayed(pushSize, 600)
                        view.postDelayed(pushSize, 1500)
                        // Debounce resize: keyboard animation fires one layout change per frame
                        // (~25 events). Only send RESIZE after the view has been stable for 150ms.
                        val resizeHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        var pendingResize: Runnable? = null
                        val listener = android.view.View.OnLayoutChangeListener { v, left, top, right, bottom,
                                                         oldLeft, oldTop, oldRight, oldBottom ->
                            val w = right - left
                            val h = bottom - top
                            if (w <= 0 || h <= 0) return@OnLayoutChangeListener
                            if (w == oldRight - oldLeft && h == oldBottom - oldTop) return@OnLayoutChangeListener
                            pendingResize?.let { resizeHandler.removeCallbacks(it) }
                            val r = Runnable {
                                viewModel.forceUpdateSizeFromView(v as TerminalView)
                                v.updateSize()
                            }
                            pendingResize = r
                            resizeHandler.postDelayed(r, 150)
                        }
                        view.addOnLayoutChangeListener(listener)
                        onDispose {
                            pendingResize?.let { resizeHandler.removeCallbacks(it) }
                            view.removeOnLayoutChangeListener(listener)
                        }
                    }

                    AndroidView(
                        factory = { view },
                        update = { v ->
                            v.setTextSize(fontSize)
                            // setTextSize immediately recreates mRenderer with new metrics.
                            // Use v.updateSize() directly — it reads from mRenderer (correct),
                            // avoiding the off-by-row conflict with forceUpdateSizeFromView's
                            // different line-height formula. Multiple delays ensure the VM's
                            // resize daemon receives the message even under load.
                            v.post { v.updateSize() }
                            v.postDelayed({ v.updateSize() }, 300)
                            v.postDelayed({ v.updateSize() }, 800)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (viewModel.showExtraKeys) {
            AdaptiveContainer(
                windowSizeClass = windowSizeClass,
                maxWidth = 800
            ) {
                ExtraKeysRow(
                    onKey = { viewModel.sendExtraKey(it) },
                    ctrlActive = viewModel.extraCtrl,
                    altActive = viewModel.extraAlt,
                )
            }
        }
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
        KeyButton("ESC", onKey)
        KeyButton("TAB", onKey)
        KeyButton("CTRL", onKey, isActive = ctrlActive)
        KeyButton("\u2190", onKey, sendKey = "LEFT",  repeatable = true)
        KeyButton("\u2191", onKey, sendKey = "UP",    repeatable = true)
        KeyButton("\u2193", onKey, sendKey = "DOWN",  repeatable = true)
        KeyButton("\u2192", onKey, sendKey = "RIGHT", repeatable = true)
        KeyButton("ALT", onKey, isActive = altActive)
        KeyButton("-", onKey)
        KeyButton("/", onKey)
        KeyButton("|", onKey)
        KeyButton("HOME", onKey)
        KeyButton("END", onKey)
        KeyButton("PGUP", onKey)
        KeyButton("PGDN", onKey)
        KeyButton("F1", onKey)
        KeyButton("F2", onKey)
        KeyButton("F3", onKey)
        KeyButton("F4", onKey)
        KeyButton("F5", onKey)
        KeyButton("F6", onKey)
        KeyButton("F7", onKey)
        KeyButton("F8", onKey)
        KeyButton("F9", onKey)
        KeyButton("F10", onKey)
        KeyButton("F11", onKey)
        KeyButton("F12", onKey)
    }
}

@Composable
private fun KeyButton(
    label: String,
    onKey: (String) -> Unit,
    sendKey: String = label,
    isActive: Boolean = false,
    repeatable: Boolean = false,
) {
    // State-based press tracking: the gesture coroutine flips `pressed`,
    // and a LaunchedEffect keyed on that state drives the repeat loop.
    // Splitting state from the pointer coroutine avoids the cancellation
    // issues you hit when the scroll container on the parent Row steals
    // the gesture mid-hold.
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, sendKey, repeatable) {
        if (!repeatable || !pressed) return@LaunchedEffect
        delay(400L)
        var interval = 70L
        while (pressed) {
            onKey(sendKey)
            delay(interval)
            if (interval > 30L) interval -= 3L
        }
    }
    val tapModifier = if (repeatable) {
        Modifier.pointerInput(sendKey) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                onKey(sendKey)          // fire once on touch-down
                pressed = true
                try {
                    waitForUpOrCancellation()
                } finally {
                    pressed = false
                }
            }
        }
    } else {
        Modifier.clickable { onKey(sendKey) }
    }
    Text(
        text = label,
        color = if (isActive) Color.Black else Color(0xFFCCCCCC),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isActive) Color(0xFF4FC3F7) else Color(0xFF333333))
            .then(tapModifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun QuickSettingsDialog(
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    showExtraKeys: Boolean,
    onToggleExtraKeys: (Boolean) -> Unit,
    hapticsEnabled: Boolean,
    onToggleHaptics: (Boolean) -> Unit,
    colorTheme: String,
    onColorThemeChange: (String) -> Unit,
    terminalFont: String,
    onFontChange: (String) -> Unit,
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    
    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = colorTheme,
            onThemeSelect = {
                onColorThemeChange(it)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }
    
    if (showFontDialog) {
        FontSelectionDialog(
            currentFont = terminalFont,
            onFontSelect = {
                onFontChange(it)
                showFontDialog = false
            },
            onDismiss = { showFontDialog = false }
        )
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick Settings") },
        text = {
            Column {
                Text("Font Size: $fontSize sp", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { onFontSizeChange(it.toInt()) },
                    valueRange = 12f..32f,
                    steps = 20,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showThemeDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Theme")
                    }
                    FilledTonalButton(
                        onClick = { showFontDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Font")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show extra keys", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = showExtraKeys,
                        onCheckedChange = onToggleExtraKeys
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Haptic feedback", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = hapticsEnabled,
                        onCheckedChange = onToggleHaptics
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun ThemeSelectionDialog(
    currentTheme: String,
    onThemeSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val themes = remember {
        listOf("default") + (context.assets.list("colors")?.toList()
            ?.filter { it.endsWith(".properties") }
            ?.mapNotNull { it?.removeSuffix(".properties") }
            ?.sorted() ?: emptyList())
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Theme") },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                items(themes.size) { index ->
                    val theme = themes[index]
                    val isSelected = theme == currentTheme
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { onThemeSelect(theme) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = theme.replace('-', ' ').replaceFirstChar { it.uppercase() },
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FontSelectionDialog(
    currentFont: String,
    onFontSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val fonts = remember {
        listOf("default") + (context.assets.list("fonts")?.toList()
            ?.filter { it.endsWith(".ttf") }
            ?.mapNotNull { it?.removeSuffix(".ttf") }
            ?.sorted() ?: emptyList())
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Font") },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                items(fonts.size) { index ->
                    val font = fonts[index]
                    val isSelected = font == currentFont
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { onFontSelect(font) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = font.replace('-', ' ').replaceFirstChar { it.uppercase() },
                            fontFamily = FontFamily.Monospace,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

