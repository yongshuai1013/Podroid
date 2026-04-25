/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Terminal ViewModel — wires TerminalView to the podroid-bridge binary.
 *
 * The bridge binary (libpodroid-bridge.so) runs as the TerminalSession
 * subprocess. Termux allocates a real PTY for it; the bridge relays that
 * PTY to QEMU's virtio-console terminal.sock (= /dev/hvc0 in the VM).
 * Window resize is handled out-of-band over a second virtio-console port:
 *
 *   TerminalSession.updateSize(cols, rows)
 *     → ioctl(pty_master, TIOCSWINSZ)          [Termux JNI]
 *     → SIGWINCH → bridge process
 *     → bridge debounces (RESIZE_DEBOUNCE_MS, currently 200 ms) so a
 *       keyboard-slide animation collapses to one event
 *     → reads final size via TIOCGWINSZ
 *     → writes "RESIZE rows cols\n" to ctrl.sock (= /dev/hvc1 in the VM)
 *     → init-podroid resize daemon calls stty on /dev/hvc0
 *     → Linux sends SIGWINCH to the VM's foreground process group
 *     → nvim / htop / btop redraws correctly
 *
 * No reflection, no emulator injection, no sz stdin injection.
 */
package com.excp.podroid.ui.screens.terminal

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalColors
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import com.excp.podroid.util.LogProxy
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val qemu: PodroidQemu,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val vmState: StateFlow<VmState> = qemu.state
    val bootStage: StateFlow<String> = qemu.bootStage
    val terminalFontSize: StateFlow<Int> = settingsRepository.terminalFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)

    val terminalColorTheme: StateFlow<String> = settingsRepository.terminalColorTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    val terminalFont: StateFlow<String> = settingsRepository.terminalFont
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    // Persisted across sessions — was previously transient in-memory only.
    val showExtraKeysFlow: StateFlow<Boolean> = settingsRepository.showExtraKeys
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val hapticsEnabledFlow: StateFlow<Boolean> = settingsRepository.hapticsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Mirrors of the persisted flows for callers that want a synchronous read. */
    val showExtraKeys: Boolean get() = showExtraKeysFlow.value
    val hapticsEnabled: Boolean get() = hapticsEnabledFlow.value

    // Trigger for opening the Quick Settings drawer (composable-side reacts via StateFlow)
    private val _showQuickSettings = kotlinx.coroutines.flow.MutableStateFlow(false)
    val showQuickSettings = _showQuickSettings

    // Quick settings helpers (non-persistent)
    fun openQuickSettings() { _showQuickSettings.value = true }
    fun closeQuickSettings() { _showQuickSettings.value = false }

    fun updateShowExtraKeys(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowExtraKeys(value) }
    }
    fun updateHapticsEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setHapticsEnabled(value) }
    }

    fun setTerminalFontSize(value: Int) {
        viewModelScope.launch {
            settingsRepository.setTerminalFontSize(value)
        }
    }

    fun setTerminalColorTheme(value: String) {
        viewModelScope.launch { settingsRepository.setTerminalColorTheme(value) }
    }

    fun setTerminalFont(value: String) {
        viewModelScope.launch { settingsRepository.setTerminalFont(value) }
    }

    private var attached = false

    /**
     * Weak handle to the currently-attached TerminalView. Used only by client
     * callbacks that need to call onScreenUpdated() / showSoftInput(), which
     * fire from non-UI threads. WeakReference prevents leaking the destroyed
     * Activity across config changes.
     */
    private var viewRef: java.lang.ref.WeakReference<TerminalView>? = null

    fun bindView(view: TerminalView?) {
        viewRef = view?.let { java.lang.ref.WeakReference(it) }
    }

    /**
     * Resolve a theme name to the (background, Properties) pair, also pushing
     * the palette into TerminalColors.COLOR_SCHEME so the renderer picks it up.
     * Returns null background for the built-in default.
     */
    fun loadColorTheme(theme: String): Int? = if (theme == "default") {
        null
    } else try {
        val props = java.util.Properties()
        context.assets.open("colors/$theme.properties").use { props.load(it) }
        TerminalColors.COLOR_SCHEME.updateWith(props)
        (props["background"] as? String)?.let { parseColor(it) }
    } catch (_: Exception) { null }

    /** Lists asset files in `dir` whose name ends with `suffix`, with the suffix stripped. */
    fun listAssetNames(dir: String, suffix: String): List<String> {
        val items = try {
            context.assets.list(dir)?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }
        return listOf("default") + items.filter { it.endsWith(suffix) }.map { it.removeSuffix(suffix) }.sorted()
    }

    /** Resolve a font name to a Typeface. Returns Typeface.MONOSPACE for default or on error. */
    fun loadFont(font: String): Typeface = if (font == "default") {
        Typeface.MONOSPACE
    } else try {
        // assets.openFd is a file descriptor — Typeface.createFromFile needs a real path,
        // so we copy on demand into a per-launch cache file. Same disk hit as before but
        // no DataStore-shadowed state on filesDir.
        val cacheFile = java.io.File(context.cacheDir, "font_$font.ttf")
        if (!cacheFile.exists()) {
            context.assets.open("fonts/$font.ttf").use { inp ->
                cacheFile.outputStream().use { out -> inp.copyTo(out) }
            }
        }
        Typeface.createFromFile(cacheFile)
    } catch (_: Exception) { Typeface.MONOSPACE }

    private fun parseColor(hex: String): Int {
        val clean = hex.removePrefix("#")
        return when (clean.length) {
            3 -> {
                val r = clean[0].digitToIntOrNull(16) ?: return android.graphics.Color.BLACK
                val g = clean[1].digitToIntOrNull(16) ?: return android.graphics.Color.BLACK
                val b = clean[2].digitToIntOrNull(16) ?: return android.graphics.Color.BLACK
                android.graphics.Color.rgb(r * 17, g * 17, b * 17)
            }
            6 -> {
                android.graphics.Color.rgb(
                    clean.substring(0, 2).toInt(16),
                    clean.substring(2, 4).toInt(16),
                    clean.substring(4, 6).toInt(16)
                )
            }
            8 -> {
                android.graphics.Color.argb(
                    clean.substring(0, 2).toInt(16),
                    clean.substring(2, 4).toInt(16),
                    clean.substring(4, 6).toInt(16),
                    clean.substring(6, 8).toInt(16)
                )
            }
            else -> android.graphics.Color.BLACK
        }
    }

    var session: TerminalSession? = null
        private set

    var extraCtrl by mutableStateOf(false)
        private set
    var extraAlt by mutableStateOf(false)
        private set

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            viewRef?.get()?.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text == null) return
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            // Route through TerminalEmulator.paste() — it wraps in CSI 200~ / 201~
            // when bracketed-paste mode is on (nvim, bash) and sends raw otherwise.
            // Fall back to raw write if the emulator isn't ready yet.
            val emu = session?.emulator
            if (emu != null) emu.paste(text) else session?.write(text)
        }
        override fun onBell(session: TerminalSession) {
            if (hapticsEnabled) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) = LogProxy.error(tag, TAG, message)
        override fun logWarn(tag: String?, message: String?) = LogProxy.warn(tag, TAG, message)
        override fun logInfo(tag: String?, message: String?) = LogProxy.info(tag, TAG, message)
        override fun logDebug(tag: String?, message: String?) = LogProxy.debug(tag, TAG, message)
        override fun logVerbose(tag: String?, message: String?) = LogProxy.verbose(tag, TAG, message)
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
            LogProxy.stackTraceWithMessage(tag, TAG, message, e)
        override fun logStackTrace(tag: String?, e: Exception?) = LogProxy.stackTrace(tag, TAG, e)
    }

    val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent?) {
            val view = viewRef?.get() ?: return
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(view, 0)
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (e == null) return false
            val shift = e.isShiftPressed
            val ctrl = e.isCtrlPressed || extraCtrl
            val alt = e.isAltPressed || extraAlt
            // xterm CSI modifier: 1=none, 2=shift, 3=alt, 4=shift+alt, 5=ctrl,
            // 6=ctrl+shift, 7=ctrl+alt, 8=all. Used for "ESC [1;<m><final>".
            val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
            fun arrow(final: Char): ByteArray =
                if (mod == 1) "\u001b[$final".toByteArray()
                else "\u001b[1;$mod$final".toByteArray()

            val bytes = when (keyCode) {
                KeyEvent.KEYCODE_ENTER        -> byteArrayOf(13)
                KeyEvent.KEYCODE_DEL          -> byteArrayOf(127)
                KeyEvent.KEYCODE_FORWARD_DEL  -> "\u001b[3~".toByteArray()
                KeyEvent.KEYCODE_TAB          ->
                    if (shift) "\u001b[Z".toByteArray() else byteArrayOf(9)
                KeyEvent.KEYCODE_ESCAPE       -> byteArrayOf(27)
                KeyEvent.KEYCODE_DPAD_UP      -> arrow('A')
                KeyEvent.KEYCODE_DPAD_DOWN    -> arrow('B')
                KeyEvent.KEYCODE_DPAD_RIGHT   -> arrow('C')
                KeyEvent.KEYCODE_DPAD_LEFT    -> arrow('D')
                KeyEvent.KEYCODE_MOVE_HOME    -> arrow('H')
                KeyEvent.KEYCODE_MOVE_END     -> arrow('F')
                KeyEvent.KEYCODE_PAGE_UP      -> "\u001b[5~".toByteArray()
                KeyEvent.KEYCODE_PAGE_DOWN    -> "\u001b[6~".toByteArray()
                KeyEvent.KEYCODE_INSERT       -> "\u001b[2~".toByteArray()
                KeyEvent.KEYCODE_F1           -> "\u001bOP".toByteArray()
                KeyEvent.KEYCODE_F2           -> "\u001bOQ".toByteArray()
                KeyEvent.KEYCODE_F3           -> "\u001bOR".toByteArray()
                KeyEvent.KEYCODE_F4           -> "\u001bOS".toByteArray()
                KeyEvent.KEYCODE_F5           -> "\u001b[15~".toByteArray()
                KeyEvent.KEYCODE_F6           -> "\u001b[17~".toByteArray()
                KeyEvent.KEYCODE_F7           -> "\u001b[18~".toByteArray()
                KeyEvent.KEYCODE_F8           -> "\u001b[19~".toByteArray()
                KeyEvent.KEYCODE_F9           -> "\u001b[20~".toByteArray()
                KeyEvent.KEYCODE_F10          -> "\u001b[21~".toByteArray()
                KeyEvent.KEYCODE_F11          -> "\u001b[23~".toByteArray()
                KeyEvent.KEYCODE_F12          -> "\u001b[24~".toByteArray()
                else -> null
            }
            if (bytes != null) {
                session?.write(bytes, 0, bytes.size)
                if (mod != 1) { extraCtrl = false; extraAlt = false }
                return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = extraCtrl
        override fun readAltKey(): Boolean = extraAlt
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            val bytes: ByteArray
            if ((ctrlDown || extraCtrl) && codePoint in 64..127) {
                bytes = byteArrayOf((codePoint and 0x1f).toByte())
            } else {
                val charBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                bytes = if (extraAlt) byteArrayOf(27) + charBytes else charBytes
            }
            session?.write(bytes, 0, bytes.size)
            extraCtrl = false
            extraAlt = false
            return true
        }

        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) = LogProxy.error(tag, TAG, message)
        override fun logWarn(tag: String?, message: String?) = LogProxy.warn(tag, TAG, message)
        override fun logInfo(tag: String?, message: String?) = LogProxy.info(tag, TAG, message)
        override fun logDebug(tag: String?, message: String?) = LogProxy.debug(tag, TAG, message)
        override fun logVerbose(tag: String?, message: String?) = LogProxy.verbose(tag, TAG, message)
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
            LogProxy.stackTraceWithMessage(tag, TAG, message, e)
        override fun logStackTrace(tag: String?, e: Exception?) = LogProxy.stackTrace(tag, TAG, e)
    }

    fun createSession() {
        if (attached) return

        val sess = qemu.createTerminalSession(sessionClient)
        session = sess
        attached = true
    }

    /**
     * Force-create a new session. Called when the VM restarts to replace the
     * stale session from the previous run with a fresh one.
     */
    fun resetOnRestart() {
        attached = false
        session = null
    }

    /**
     * Compute cols/rows from view pixel dimensions + paint metrics and push
     * directly to the session, bypassing TerminalView.updateSize() which depends
     * on mRenderer being lazily initialized during the first draw. When the
     * session is attached before first paint, updateSize() computes zero sizes,
     * leaves the emulator at the initial 80x24 default, and TUI apps render
     * into the wrong grid (visible symptom: btop draws in the top-left only,
     * because the emulator is larger than the visible viewport).
     */
    fun forceUpdateSizeFromView(view: TerminalView, typeface: Typeface = Typeface.MONOSPACE) {
        val sess = session ?: return
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            Log.d(TAG, "forceUpdateSize: view not measured yet (${w}x${h})")
            return
        }
        // Same typeface + raw pixel size as TerminalView.setTextSize(int) — which passes
        // the int straight to Paint.setTextSize() without scaledDensity. Custom fonts
        // (JetBrains Mono, Fira Code, etc.) have different cell metrics from monospace,
        // so honor the view's actual typeface or TUI apps render in the wrong grid.
        val paint = android.graphics.Paint().apply {
            this.typeface = typeface
            textSize = terminalFontSize.value.toFloat()
            isAntiAlias = true
        }
        val charWidth = paint.measureText("M").coerceAtLeast(1f)
        val fm = paint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent + fm.leading).coerceAtLeast(1f)
        val cols = (w / charWidth).toInt().coerceAtLeast(20)
        val rows = (h / lineHeight).toInt().coerceAtLeast(8)
        Log.d(TAG, "forceUpdateSize: view=${w}x${h}px cell=${charWidth}x${lineHeight}px → ${cols}x${rows} chars")
        sess.updateSize(cols, rows)
    }

    /**
     * Emit xterm focus-in/out (CSI I / CSI O) when the app gains/loses focus.
     * nvim's `FocusGained` / `FocusLost` autocommands rely on these. DECSET 1004
     * (`DECSET_BIT_SEND_FOCUS_EVENTS`) and `isDecsetInternalBitSet` are private
     * in the Termux AAR, so we read the `mCurrentDecSetFlags` field reflectively
     * and mask with the known bit. If the reflection ever breaks we silently
     * skip — sending focus bytes to a shell that didn't enable reporting would
     * leak literal "^[[I" noise into the prompt.
     */
    fun sendFocusEvent(focused: Boolean) {
        val sess = session ?: return
        val emu = sess.emulator ?: return
        if (!isFocusReportingEnabled(emu)) return
        val seq = if (focused) "\u001b[I".toByteArray() else "\u001b[O".toByteArray()
        sess.write(seq, 0, seq.size)
    }

    private fun isFocusReportingEnabled(emu: TerminalEmulator): Boolean = try {
        val cls = TerminalEmulator::class.java
        val flagsField = cls.getDeclaredField("mCurrentDecSetFlags").apply { isAccessible = true }
        // Termux maps the public DECSET code 1004 via mapDecSetBitToInternalBit — we can't
        // call that (package-private), so we derive the bit by setting 1004 through
        // the public doDecSetOrReset path and observing the delta. Cheaper: invoke the
        // package-private mapper reflectively.
        val mapper = cls.getDeclaredMethod("mapDecSetBitToInternalBit", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val bit = mapper.invoke(null, 1004) as Int
        val flags = flagsField.getInt(emu)
        (flags and bit) != 0
    } catch (_: Throwable) { false }

    fun sendExtraKey(key: String) {
        when (key) {
            "CTRL" -> { extraCtrl = !extraCtrl; return }
            "ALT"  -> { extraAlt = !extraAlt; return }
        }
        val bytes = when (key) {
            "ESC"  -> byteArrayOf(27)
            "TAB"  -> byteArrayOf(9)
            "UP"   -> "\u001b[A".toByteArray()
            "DOWN" -> "\u001b[B".toByteArray()
            "LEFT" -> "\u001b[D".toByteArray()
            "RIGHT"-> "\u001b[C".toByteArray()
            "HOME" -> "\u001b[H".toByteArray()
            "END"  -> "\u001b[F".toByteArray()
            "PGUP" -> "\u001b[5~".toByteArray()
            "PGDN" -> "\u001b[6~".toByteArray()
            "F1"   -> "\u001bOP".toByteArray()
            "F2"   -> "\u001bOQ".toByteArray()
            "F3"   -> "\u001bOR".toByteArray()
            "F4"   -> "\u001bOS".toByteArray()
            "F5"   -> "\u001b[15~".toByteArray()
            "F6"   -> "\u001b[17~".toByteArray()
            "F7"   -> "\u001b[18~".toByteArray()
            "F8"   -> "\u001b[19~".toByteArray()
            "F9"   -> "\u001b[20~".toByteArray()
            "F10"  -> "\u001b[21~".toByteArray()
            "F11"  -> "\u001b[23~".toByteArray()
            "F12"  -> "\u001b[24~".toByteArray()
            "-"    -> "-".toByteArray()
            "|"    -> "|".toByteArray()
            "/"    -> "/".toByteArray()
            else   -> return
        }
        session?.write(bytes, 0, bytes.size)
        extraCtrl = false
        extraAlt = false
    }

    override fun onCleared() {
        super.onCleared()
        // Drop the proxy's pointer to this dead ViewModel — otherwise the singleton
        // PodroidQemu keeps forwarding session events into a tombstoned client.
        if (qemu.sessionClientDelegate === sessionClient) {
            qemu.sessionClientDelegate = null
        }
        attached = false
    }

    companion object {
        private const val TAG = "TerminalVM"
    }
}
