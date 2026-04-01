# Podroid ProGuard rules

# Termux terminal-emulator fields accessed via reflection
-keepclassmembers class com.termux.terminal.TerminalSession {
    private com.termux.terminal.TerminalEmulator mEmulator;
}

# Termux terminal-view renderer fields accessed via reflection for size calculation
-keepclassmembers class com.termux.view.TerminalRenderer {
    private float mFontWidth;
    private int mFontLineSpacing;
    private int mFontLineSpacingAndAscent;
}

# TerminalView fields set directly from TerminalScreen
-keepclassmembers class com.termux.view.TerminalView {
    public com.termux.terminal.TerminalSession mTermSession;
    public com.termux.terminal.TerminalEmulator mEmulator;
    private com.termux.view.TerminalRenderer mRenderer;
}
