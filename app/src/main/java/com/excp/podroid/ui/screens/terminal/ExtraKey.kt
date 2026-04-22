package com.excp.podroid.ui.screens.terminal

import android.util.Base64

/**
 * Data model for customizable extra keys in the terminal toolbar.
 */
data class ExtraKey(
    val label: String,
    val action: KeyAction,
    val modifier: Modifier? = null
)

/**
 * Actions that an extra key can perform.
 */
sealed class KeyAction {
    /** Send an escape sequence */
    data class SendEscape(val sequence: String) : KeyAction()
    
    /** Send Ctrl+C */
    object CtrlC : KeyAction()
    
    /** Send Ctrl+D */
    object CtrlD : KeyAction()
    
    /** Send Ctrl+Z */
    object CtrlZ : KeyAction()
    
    /** Send raw bytes */
    data class Custom(val bytes: ByteArray) : KeyAction()
    
    /** Toggle modifier only (Ctrl, Alt, Shift) */
    data class ModifierOnly(val modifier: Modifier) : KeyAction()
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KeyAction
        return when (this) {
            is SendEscape -> other is SendEscape && sequence == other.sequence
            is CtrlC -> other is CtrlC
            is CtrlD -> other is CtrlD
            is CtrlZ -> other is CtrlZ
            is Custom -> other is Custom && bytes.contentEquals(other.bytes)
            is ModifierOnly -> other is ModifierOnly && modifier == other.modifier
        }
    }
    
    override fun hashCode(): Int {
        return when (this) {
            is SendEscape -> sequence.hashCode()
            is CtrlC -> CtrlC.hashCode()
            is CtrlD -> CtrlD.hashCode()
            is CtrlZ -> CtrlZ.hashCode()
            is Custom -> bytes.contentHashCode()
            is ModifierOnly -> modifier.hashCode()
        }
    }
}

/**
 * Modifier keys that can be toggled.
 */
enum class Modifier { CTRL, ALT, SHIFT }

/**
 * Serialization helpers for ExtraKey to/from JSON.
 */
object ExtraKeySerde {
    
    /**
     * Parse a JSON string into a list of ExtraKeys.
     */
    fun fromJson(json: String): List<ExtraKey> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<ExtraKey>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val label = obj.getString("label")
                val mod = if (obj.has("modifier")) {
                    Modifier.valueOf(obj.getString("modifier"))
                } else null
                
                val actionObj = obj.getJSONObject("action")
                val action: KeyAction = when (actionObj.getString("type")) {
                    "SendEscape" -> KeyAction.SendEscape(actionObj.getString("sequence"))
                    "CtrlC" -> KeyAction.CtrlC
                    "CtrlD" -> KeyAction.CtrlD
                    "CtrlZ" -> KeyAction.CtrlZ
                    "Custom" -> KeyAction.Custom(Base64.decode(actionObj.getString("bytes"), Base64.NO_WRAP))
                    "ModifierOnly" -> KeyAction.ModifierOnly(Modifier.valueOf(actionObj.getString("modifier")))
                    else -> KeyAction.CtrlC
                }
                list.add(ExtraKey(label, action, mod))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Serialize a list of ExtraKeys to JSON string.
     */
    fun toJson(keys: List<ExtraKey>): String {
        val arr = org.json.JSONArray()
        for (k in keys) {
            val obj = org.json.JSONObject()
            obj.put("label", k.label)
            k.modifier?.let { obj.put("modifier", it.name) }
            
            val actionObj = org.json.JSONObject()
            when (val a = k.action) {
                is KeyAction.SendEscape -> {
                    actionObj.put("type", "SendEscape")
                    actionObj.put("sequence", a.sequence)
                }
                is KeyAction.CtrlC -> actionObj.put("type", "CtrlC")
                is KeyAction.CtrlD -> actionObj.put("type", "CtrlD")
                is KeyAction.CtrlZ -> actionObj.put("type", "CtrlZ")
                is KeyAction.Custom -> {
                    actionObj.put("type", "Custom")
                    actionObj.put("bytes", Base64.encodeToString(a.bytes, Base64.NO_WRAP))
                }
                is KeyAction.ModifierOnly -> {
                    actionObj.put("type", "ModifierOnly")
                    actionObj.put("modifier", a.modifier.name)
                }
            }
            obj.put("action", actionObj)
            arr.put(obj)
        }
        return arr.toString()
    }
}
