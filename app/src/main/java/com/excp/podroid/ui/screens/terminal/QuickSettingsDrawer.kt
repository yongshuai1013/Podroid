package com.excp.podroid.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

/**
 * Quick Settings Drawer content for the Terminal screen.
 * This composable is used as the sheet content of a bottom sheet.
 */
@Composable
fun QuickSettingsDrawer(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    // Settings from SettingsRepository via ViewModel
    fontSize: Int,
    colorTheme: String,
    onFontSizeChange: (Int) -> Unit,
    onColorThemeChange: (String) -> Unit,
    onToggleExtraKeys: (Boolean) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onToggleKeepScreen: (Boolean) -> Unit,
    // Additional ephemeral UI state (from ViewModel)
    showExtraKeys: Boolean,
    hapticsEnabled: Boolean,
    keepScreenOnEnabled: Boolean,
){
    // Only render when sheet is visible; the ModalBottomSheetLayout handles visibility, but we keep a guard for safety
    if (!isVisible) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(text = "Quick Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        // Font size slider
        Text(text = "Font size", fontSize = 14.sp, color = Color(0xFFCCCCCC))
        Spacer(modifier = Modifier.height(6.dp))
        Slider(
            value = fontSize.toFloat(),
            onValueChange = { onFontSizeChange(it.toInt()) },
            valueRange = 16f..32f,
            steps = 16
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Theme chips
        Text(text = "Theme", fontSize = 14.sp, color = Color(0xFFCCCCCC))
        Spacer(modifier = Modifier.height(6.dp))
        val themes = listOf("default","Dracula","Nord","Gruvbox","Solarized","Tokyo Night")
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(themes) { t ->
                val selected = t == colorTheme
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(if (selected) Color(0xFF4FC3F7) else Color(0xFF333333), RoundedCornerShape(999.dp))
                        .clickable { onColorThemeChange(t) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(text = t, fontSize = 12.sp, color = Color.White)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Extra keys bar toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Show extra keys bar", fontSize = 14.sp, color = Color(0xFFCCCCCC), modifier = Modifier.weight(1f))
            Switch(
                checked = showExtraKeys,
                onCheckedChange = { checked -> onToggleExtraKeys(checked) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Haptic feedback
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Haptic feedback", fontSize = 14.sp, color = Color(0xFFCCCCCC), modifier = Modifier.weight(1f))
            Switch(
                checked = hapticsEnabled,
                onCheckedChange = { enabled -> onToggleHaptics(enabled) },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Keep screen on
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Keep screen on", fontSize = 14.sp, color = Color(0xFFCCCCCC), modifier = Modifier.weight(1f))
            Switch(
                checked = keepScreenOnEnabled,
                onCheckedChange = { enabled -> onToggleKeepScreen(enabled) }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
