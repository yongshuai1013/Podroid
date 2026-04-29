package com.excp.podroid.ui.screens.terminal

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
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
    val showExtraKeys by viewModel.showExtraKeysFlow.collectAsState()
    val hapticsEnabled by viewModel.hapticsEnabledFlow.collectAsState()

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

    // Resolve the typeface and background palette from settings. Both run on
    // recomposition because they're cheap (asset reads, font cache lookup) and
    // the result drives the View setters in LaunchedEffect below.
    val typeface = remember(terminalFont) { viewModel.loadFont(terminalFont) }
    val themeBg = remember(colorTheme) { viewModel.loadColorTheme(colorTheme) }

    if (showQuickSettings) {
        QuickSettingsDialog(
            fontSize = fontSize,
            onFontSizeChange = { viewModel.setTerminalFontSize(it) },
            onDismiss = { viewModel.closeQuickSettings() },
            showExtraKeys = showExtraKeys,
            onToggleExtraKeys = { viewModel.updateShowExtraKeys(it) },
            hapticsEnabled = hapticsEnabled,
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
            title = { Text("Terminal") },
            navigationIcon = {
                IconButton(onClick = {
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    (context as? Activity)?.currentFocus?.let {
                        imm.hideSoftInputFromWindow(it.windowToken, 0)
                    }
                    onNavigateBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { viewModel.openQuickSettings() }) {
                    Text("⚙", fontSize = 18.sp)
                }
            },
        )

        when (vmState) {
            is VmState.Idle, is VmState.Stopped -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "VM Not Running",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Start the VM from Home screen first",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            is VmState.Error -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Error",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            (vmState as VmState.Error).message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(onClick = onNavigateBack) {
                            Text("Tap to Retry")
                        }
                    }
                }
            }

            is VmState.Starting -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
            }

            is VmState.Running -> {
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    // The View is created per-Composition-context (i.e. per Activity).
                    // Caching it across config changes used to leak the destroyed Activity.
                    val view = remember(context) {
                        TerminalView(context, null).apply {
                            setTextSize(fontSize)
                            keepScreenOn = true
                            isFocusable = true
                            isFocusableInTouchMode = true
                        }
                    }

                    LaunchedEffect(view, themeBg) {
                        view.setBackgroundColor(themeBg ?: android.graphics.Color.BLACK)
                    }
                    LaunchedEffect(view, typeface) {
                        view.setTypeface(typeface)
                        view.post {
                            viewModel.forceUpdateSizeFromView(view, typeface)
                            view.updateSize()
                        }
                    }

                    DisposableEffect(view, vmState) {
                        viewModel.resetOnRestart()
                        viewModel.bindView(view)
                        viewModel.createSession()
                        view.setTerminalViewClient(viewModel.viewClient)
                        val sess = viewModel.session
                        if (sess != null) {
                            view.mTermSession = sess
                            view.mEmulator = sess.emulator
                        }
                        view.requestFocus()
                        view.onScreenUpdated()

                        view.post {
                            viewModel.forceUpdateSizeFromView(view, typeface)
                            view.updateSize()
                            view.onScreenUpdated()
                        }
                        onDispose { viewModel.bindView(null) }
                    }

                    // Layout-change debounce: keyboard slide animation fires ~25 layout
                    // events. Coroutine debounce collapses them to one SIGWINCH.
                    val scope = rememberCoroutineScope()
                    DisposableEffect(view) {
                        var pending: kotlinx.coroutines.Job? = null
                        val listener = android.view.View.OnLayoutChangeListener {
                            v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                            val w = right - left
                            val h = bottom - top
                            if (w <= 0 || h <= 0) return@OnLayoutChangeListener
                            if (w == oldRight - oldLeft && h == oldBottom - oldTop) return@OnLayoutChangeListener
                            pending?.cancel()
                            pending = scope.launch {
                                kotlinx.coroutines.delay(150)
                                val tv = v as TerminalView
                                viewModel.forceUpdateSizeFromView(tv, typeface)
                                tv.updateSize()
                            }
                        }
                        view.addOnLayoutChangeListener(listener)
                        onDispose {
                            pending?.cancel()
                            view.removeOnLayoutChangeListener(listener)
                        }
                    }

                    AndroidView(
                        factory = { view },
                        update = { v ->
                            v.setTextSize(fontSize)
                            v.post { v.updateSize() }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (showExtraKeys) {
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
        color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
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
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val themes = remember { viewModel.listAssetNames("colors", ".properties") }
    val fonts = remember { viewModel.listAssetNames("fonts", ".ttf") }
    val fontSizes = listOf(12, 14, 16, 18, 20, 22, 24, 28, 32)

    val scrollState = rememberScrollState()
    val scrollbarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val scrollTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick Settings") },
        text = {
            Box(
                modifier = Modifier.verticalScrollbar(
                    state = scrollState,
                    thumbColor = scrollbarColor,
                    trackColor = scrollTrackColor,
                ),
            ) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // ── Font size ──────────────────────────────────────
                val sizeIdx = fontSizes.indexOf(fontSize).coerceAtLeast(0)
                val sizeListState = rememberLazyListState(sizeIdx)
                QuickSettingsRow(label = "Font size", value = "$fontSize sp") {
                    LazyRow(
                        state = sizeListState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.fadingEdges(sizeListState),
                    ) {
                        items(fontSizes.size) { i ->
                            val sz = fontSizes[i]
                            QuickChip(
                                label = "$sz",
                                selected = sz == fontSize,
                                onClick = { onFontSizeChange(sz) },
                            )
                        }
                    }
                }

                // ── Color theme ────────────────────────────────────
                val themeIdx = themes.indexOf(colorTheme).coerceAtLeast(0)
                val themeListState = rememberLazyListState(themeIdx)
                QuickSettingsRow(label = "Color theme", value = prettyName(colorTheme)) {
                    LazyRow(
                        state = themeListState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.fadingEdges(themeListState),
                    ) {
                        items(themes.size) { i ->
                            val t = themes[i]
                            QuickChip(
                                label = prettyName(t),
                                selected = t == colorTheme,
                                onClick = { onColorThemeChange(t) },
                            )
                        }
                    }
                }

                // ── Font family ────────────────────────────────────
                val fontIdx = fonts.indexOf(terminalFont).coerceAtLeast(0)
                val fontListState = rememberLazyListState(fontIdx)
                QuickSettingsRow(label = "Font family", value = prettyName(terminalFont)) {
                    LazyRow(
                        state = fontListState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.fadingEdges(fontListState),
                    ) {
                        items(fonts.size) { i ->
                            val f = fonts[i]
                            QuickChip(
                                label = prettyName(f),
                                selected = f == terminalFont,
                                onClick = { onFontChange(f) },
                            )
                        }
                    }
                }

                HorizontalDivider()

                // ── Switches ───────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Show extra keys", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = showExtraKeys, onCheckedChange = onToggleExtraKeys)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Haptic feedback", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = hapticsEnabled, onCheckedChange = onToggleHaptics)
                }
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
private fun QuickSettingsRow(
    label: String,
    value: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        content()
    }
}

@Composable
private fun QuickChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        shape = RoundedCornerShape(14.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/** "monokai-bright" → "Monokai bright"; trims `.properties`/`.ttf` if present. */
private fun prettyName(raw: String): String =
    raw.substringBeforeLast('.')
        .replace('-', ' ')
        .replace('_', ' ')
        .replaceFirstChar { it.uppercaseChar() }

/**
 * Horizontal gradient fade at the edges of a scrollable container, drawn only
 * on the side(s) where more content exists. Uses an offscreen layer + DstIn
 * blend so the fade actually masks the chips rather than overpainting them.
 */
private fun Modifier.fadingEdges(
    state: androidx.compose.foundation.lazy.LazyListState,
    fadeWidth: Dp = 28.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadePx = fadeWidth.toPx()
        if (state.canScrollBackward) {
            drawRect(
                topLeft = Offset.Zero,
                size = Size(fadePx, size.height),
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = fadePx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                topLeft = Offset(size.width - fadePx, 0f),
                size = Size(fadePx, size.height),
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, Color.Transparent),
                    startX = size.width - fadePx,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Thin scrollbar drawn on the LEFT edge of the container. Track is always
 * visible (so users see "scrollable area"), thumb only appears when the
 * content actually overflows. The thumb size is proportional to viewport /
 * total content; its Y offset reflects current scroll position.
 */
private fun Modifier.verticalScrollbar(
    state: androidx.compose.foundation.ScrollState,
    width: Dp = 3.dp,
    thumbColor: Color,
    trackColor: Color,
    minThumbHeight: Dp = 32.dp,
): Modifier = this.drawWithContent {
    drawContent()
    val maxValue = state.maxValue
    if (maxValue <= 0) return@drawWithContent

    val widthPx = width.toPx()
    val viewportH = size.height
    val totalH = viewportH + maxValue
    val thumbH = (viewportH * viewportH / totalH).coerceAtLeast(minThumbHeight.toPx())
    val thumbY = (state.value.toFloat() / maxValue) * (viewportH - thumbH)
    val cornerPx = widthPx / 2f

    drawRoundRect(
        color = trackColor,
        topLeft = Offset(0f, 0f),
        size = Size(widthPx, viewportH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
    )
    drawRoundRect(
        color = thumbColor,
        topLeft = Offset(0f, thumbY),
        size = Size(widthPx, thumbH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
    )
}

@Composable
private fun ThemeSelectionDialog(
    currentTheme: String,
    onThemeSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val themes = remember { viewModel.listAssetNames("colors", ".properties") }
    
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
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val fonts = remember { viewModel.listAssetNames("fonts", ".ttf") }
    
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

