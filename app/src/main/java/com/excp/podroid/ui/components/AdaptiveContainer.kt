package com.excp.podroid.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A container that constrains its content to a maximum width on large screens
 * to follow Material 3 adaptive layout best practices.
 */
@Composable
fun AdaptiveContainer(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    maxWidth: Int = 600,
    content: @Composable () -> Unit
) {
    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
    val isMedium = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = if (isExpanded || isMedium) {
                Modifier.widthIn(max = maxWidth.dp)
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            content()
        }
    }
}
