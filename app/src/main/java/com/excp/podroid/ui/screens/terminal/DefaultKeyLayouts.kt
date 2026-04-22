package com.excp.podroid.ui.screens.terminal

/**
 * Default key layouts for the terminal extra keys bar.
 */
object DefaultKeyLayouts {
    
    /**
     * Minimal layout: ESC, TAB, CTRL, ALT, arrow cluster
     */
    val minimal = listOf(
        ExtraKey("ESC", KeyAction.SendEscape("\u001B")),
        ExtraKey("TAB", KeyAction.SendEscape("\u0009")),
        ExtraKey("CTRL", KeyAction.ModifierOnly(Modifier.CTRL)),
        ExtraKey("ALT", KeyAction.ModifierOnly(Modifier.ALT)),
        ExtraKey("↑", KeyAction.SendEscape("\u001B[A")),
        ExtraKey("↓", KeyAction.SendEscape("\u001B[B")),
        ExtraKey("←", KeyAction.SendEscape("\u001B[D")),
        ExtraKey("→", KeyAction.SendEscape("\u001B[C"))
    )
    
    /**
     * Full layout: all keys including function keys and page navigation
     */
    val full = listOf(
        // Row 1 - Basic controls
        ExtraKey("ESC", KeyAction.SendEscape("\u001B")),
        ExtraKey("TAB", KeyAction.SendEscape("\u0009")),
        ExtraKey("CTRL", KeyAction.ModifierOnly(Modifier.CTRL)),
        ExtraKey("ALT", KeyAction.ModifierOnly(Modifier.ALT)),
        ExtraKey("↑", KeyAction.SendEscape("\u001B[A")),
        ExtraKey("↓", KeyAction.SendEscape("\u001B[B")),
        ExtraKey("←", KeyAction.SendEscape("\u001B[D")),
        ExtraKey("→", KeyAction.SendEscape("\u001B[C")),
        
        // Row 2 - Navigation
        ExtraKey("HOME", KeyAction.SendEscape("\u001B[H")),
        ExtraKey("END", KeyAction.SendEscape("\u001B[F")),
        ExtraKey("PgUp", KeyAction.SendEscape("\u001B[5~")),
        ExtraKey("PgDn", KeyAction.SendEscape("\u001B[6~")),
        
        // Row 3 - Function keys F1-F4
        ExtraKey("F1", KeyAction.SendEscape("\u001BOP")),
        ExtraKey("F2", KeyAction.SendEscape("\u001BOQ")),
        ExtraKey("F3", KeyAction.SendEscape("\u001BOR")),
        ExtraKey("F4", KeyAction.SendEscape("\u001BOS")),
        
        // Row 4 - F5-F12
        ExtraKey("F5", KeyAction.SendEscape("\u001B[15~")),
        ExtraKey("F6", KeyAction.SendEscape("\u001B[17~")),
        ExtraKey("F7", KeyAction.SendEscape("\u001B[18~")),
        ExtraKey("F8", KeyAction.SendEscape("\u001B[19~")),
        ExtraKey("F9", KeyAction.SendEscape("\u001B[20~")),
        ExtraKey("F10", KeyAction.SendEscape("\u001B[21~")),
        ExtraKey("F11", KeyAction.SendEscape("\u001B[23~")),
        ExtraKey("F12", KeyAction.SendEscape("\u001B[24~")),
        
        // Row 5 - Symbols
        ExtraKey("-", KeyAction.SendEscape("-")),
        ExtraKey("/", KeyAction.SendEscape("/")),
        ExtraKey("|", KeyAction.SendEscape("|")),
        
        // Row 6 - Special
        ExtraKey("^C", KeyAction.CtrlC),
        ExtraKey("^D", KeyAction.CtrlD),
        ExtraKey("^Z", KeyAction.CtrlZ)
    )
    
    /**
     * Get layout by name.
     */
    fun getLayout(name: String): List<ExtraKey> = when (name) {
        "minimal" -> minimal
        "full" -> full
        else -> minimal
    }
}
