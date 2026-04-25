/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Terminal ViewModel — wires TerminalView to the podroid-bridge binary.
 *
 * The bridge binary (libpodroid-bridge.so) runs as the TerminalSession
 * subprocess. Termux allocates a real PTY for it; the bridge relays that
 * PTY to QEMU's serial Unix socket. Window resize is handled properly:
 *
 *   TerminalSession.updateSize(cols, rows)
 *     → ioctl(pty_master, TIOCSWINSZ)          [Termux JNI]
 *     → SIGWINCH → bridge process
 *     → bridge reads new size via TIOCGWINSZ
 *     → writes "RESIZE rows cols\n" to ctrl.sock
 *     → VM daemon calls stty on /dev/ttyAMA0
 *     → Linux kernel sends SIGWINCH to VM foreground process group
 *     → nvim / htop / btop redraws correctly
 *
 * No reflection, no emulator injection, no sz stdin injection.
 */
package com.excp.podroid.ui.screens.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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

    // Quick-UI state (non-persistent for now)
    var showExtraKeys by mutableStateOf(true)
        private set

    var hapticsEnabled by mutableStateOf(true)
        private set

    // Trigger for opening the Quick Settings drawer (composable-side reacts via StateFlow)
    private val _showQuickSettings = kotlinx.coroutines.flow.MutableStateFlow(false)
    val showQuickSettings = _showQuickSettings

    // Quick settings helpers (non-persistent)
    fun openQuickSettings() { _showQuickSettings.value = true }
    fun closeQuickSettings() {
        _showQuickSettings.value = false
        // Push the final terminal size now that settings are committed.
        // Covers the case where rapid slider drags sent stale intermediate sizes to the VM.
        pushSizeNow()
    }

    /**
     * Re-push the current cols/rows to the session and VM resize daemon.
     * Call after any setting that changes character cell dimensions.
     */
    fun pushSizeNow() {
        val v = terminalView ?: return
        v.post {
            v.updateSize()
            v.onScreenUpdated()
        }
    }

    fun updateShowExtraKeys(value: Boolean) { showExtraKeys = value }
    fun updateHapticsEnabled(value: Boolean) { hapticsEnabled = value }

    fun setTerminalFontSize(value: Int) {
        viewModelScope.launch {
            settingsRepository.setTerminalFontSize(value)
        }
    }

    fun setTerminalColorTheme(value: String) {
        viewModelScope.launch {
            settingsRepository.setTerminalColorTheme(value)
            copyColorThemeToFilesDirAndApply(value)
        }
    }

    fun setTerminalFont(value: String) {
        viewModelScope.launch {
            settingsRepository.setTerminalFont(value)
            copyFontToFilesDirAndApply(value)
        }
    }
    
    private suspend fun copyColorThemeToFilesDirAndApply(theme: String) {
        val colorFile = java.io.File(context.filesDir, "colors.properties")
        var bgColor = android.graphics.Color.BLACK
        if (theme == "default") {
            colorFile.delete()
        } else {
            try {
                val bytes = context.assets.open("colors/$theme.properties").use { it.readBytes() }
                val props = java.util.Properties()
                props.load(bytes.inputStream())
                TerminalColors.COLOR_SCHEME.updateWith(props)
                val bgHex = props["background"] as? String
                bgColor = bgHex?.let { parseColor(it) } ?: android.graphics.Color.BLACK
                colorFile.writeBytes(bytes)
            } catch (_: Exception) {
                colorFile.delete()
            }
        }
        terminalView?.setBackgroundColor(bgColor)
    }
    
    private suspend fun copyFontToFilesDirAndApply(font: String) {
        val fontFile = java.io.File(context.filesDir, "font.ttf")
        var typeface: Typeface = Typeface.MONOSPACE
        if (font == "default") {
            fontFile.delete()
        } else {
            try {
                context.assets.open("fonts/$font.ttf").use { inp ->
                    fontFile.outputStream().use { out -> inp.copyTo(out) }
                }
                typeface = Typeface.createFromFile(fontFile)
            } catch (_: Exception) {
                fontFile.delete()
            }
        }
        currentTypeface = typeface
        terminalView?.setTypeface(typeface)
        terminalView?.let { v ->
            v.post { forceUpdateSizeFromView(v); v.updateSize() }
        }
    }

    @SuppressLint("StaticFieldLeak") // created once per ViewModel lifetime; ViewModel is nav-scoped
    private var terminalView: TerminalView? = null
    private var attached = false

    // Tracks the active terminal typeface so forceUpdateSizeFromView uses correct char metrics.
    private var currentTypeface: Typeface = Typeface.MONOSPACE

    fun getOrCreateTerminalView(): TerminalView {
        Log.d(TAG, "getOrCreateTerminalView called, current view: $terminalView, this: ${System.identityHashCode(this)}")
        terminalView?.let { return it }

        val colorFile = java.io.File(context.filesDir, "colors.properties")
        val bgColor = if (colorFile.exists()) {
            try {
                val props = java.util.Properties()
                colorFile.inputStream().use { props.load(it) }
                TerminalColors.COLOR_SCHEME.updateWith(props)
                val bgHex = props["background"] as? String
                if (bgHex != null) parseColor(bgHex) else android.graphics.Color.BLACK
            } catch (_: Exception) { android.graphics.Color.BLACK }
        } else {
            android.graphics.Color.BLACK
        }

        val fontFile = java.io.File(context.filesDir, "font.ttf")
        val typeface = if (fontFile.exists()) {
            try { Typeface.createFromFile(fontFile) } catch (_: Exception) { Typeface.MONOSPACE }
        } else { Typeface.MONOSPACE }
        currentTypeface = typeface

        val view = TerminalView(context, null).apply {
            setBackgroundColor(bgColor)
            setTextSize(terminalFontSize.value)
            setTypeface(typeface)
            setTerminalViewClient(viewClient)
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // Reattach existing session to new view
        session?.let { sess ->
            view.mTermSession = sess
            view.mEmulator = sess.emulator
        }

        terminalView = view
        return view
    }

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

    var extraCtrl = false
    var extraAlt = false

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
            terminalView?.onScreenUpdated()
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
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Stack trace", e)
        }
    }

    val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView?.let { view ->
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(view, 0)
            }
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
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Stack trace", e)
        }
    }

    fun attachView(view: TerminalView) {
        terminalView = view
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
     * Triggered by the layout listener in TerminalScreen when the view dimensions
     * change (initial layout, keyboard open/close, rotation).
     *
     * Calls TerminalSession.updateSize which:
     *   1. Resizes the local terminal emulator buffer.
     *   2. Calls ioctl(pty_master_fd, TIOCSWINSZ) via Termux JNI.
     *   3. The kernel sends SIGWINCH to the bridge process.
     *   4. The bridge sends RESIZE to ctrl.sock → VM gets proper SIGWINCH.
     */
    fun updateSize(cols: Int, rows: Int) {
        session?.updateSize(cols, rows)
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
    fun forceUpdateSizeFromView(view: TerminalView) {
        val sess = session ?: return
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            Log.d(TAG, "forceUpdateSize: view not measured yet (${w}x${h})")
            return
        }
        // Use the same typeface + raw pixel size as TerminalView.setTextSize(int) —
        // which passes the int straight to Paint.setTextSize() without scaledDensity.
        // Must use currentTypeface (not Typeface.MONOSPACE) so that custom fonts
        // (JetBrains Mono, Fira Code, etc.) with different cell metrics produce the
        // correct cols/rows — otherwise TUI apps render in the wrong grid area.
        val paint = android.graphics.Paint().apply {
            typeface = currentTypeface
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
        terminalView = null
        attached = false
    }

    companion object {
        private const val TAG = "TerminalVM"
    }
}
