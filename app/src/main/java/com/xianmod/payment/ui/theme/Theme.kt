package com.xianmod.payment.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val XianmodColors = darkColorScheme(
    primary = Color(0xFF9B72F2),
    secondary = Color(0xFFB99AFF),
    background = Color(0xFF100D18),
    surface = Color(0xFF211A30),
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun XianmodTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XianmodColors,
        content = content
    )
}
