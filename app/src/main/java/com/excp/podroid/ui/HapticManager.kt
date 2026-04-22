package com.excp.podroid.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.delay

/**
 * Centralized haptic feedback manager for Podroid terminal interactions.
 * Provides various vibration patterns for different terminal events.
 * 
 * Features debouncing to prevent haptic spam during rapid input.
 */
class HapticManager(private val context: Context) {
    
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    
    // Debounce tracking
    private var lastVibrateTime = 0L
    private val debounceWindowMs = 30L // Minimum ms between vibrations
    
    // Vibration enabled flag
    var isEnabled: Boolean = true
    
    /**
     * Check if device can vibrate
     */
    private fun canVibrate(): Boolean {
        return isEnabled && vibrator.hasVibrator()
    }
    
    /**
     * Core vibration function with debouncing
     */
    private fun vibrate(effect: VibrationEffect) {
        if (!canVibrate()) return
        
        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < debounceWindowMs) return
        
        lastVibrateTime = now
        vibrator.vibrate(effect)
    }
    
    private fun vibrate(durationMs: Long) {
        vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }
    
    /**
     * Light tap for regular key presses
     */
    fun keyPress() {
        vibrate(5)
    }
    
    /**
     * Slightly stronger tick for extra keys bar presses
     */
    fun extraKeyPress() {
        vibrate(10)
    }
    
    /**
     * Medium pulse for long-press menu activation
     */
    fun longPressMenu() {
        vibrate(30)
    }
    
    /**
     * Double-tap confirmation for copy operations
     */
    fun copyConfirmation() {
        if (!canVibrate()) return
        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < debounceWindowMs) return
        lastVibrateTime = now
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 20, 50, 20), -1))
    }
    
    /**
     * Single tick for paste operations
     */
    fun pasteInput() {
        vibrate(15)
    }
    
    /**
     * Success feedback pulse
     */
    fun actionComplete() {
        vibrate(25)
    }
    
    /**
     * Error pattern: 50ms + pause + 50ms
     */
    fun error() {
        if (!canVibrate()) return
        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < debounceWindowMs) return
        lastVibrateTime = now
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 100, 50), -1))
    }
    
    /**
     * Bell event (for terminal bell)
     */
    fun bell() {
        vibrate(50)
    }
}
