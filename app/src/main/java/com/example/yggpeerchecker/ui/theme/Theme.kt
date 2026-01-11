package com.example.yggpeerchecker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun YggPeerCheckerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        darkTheme -> darkColorScheme(
            primary = OnlineGreen,
            secondary = AccentBlue,
            error = OfflineRed,
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E)
        )
        else -> lightColorScheme(
            primary = OnlineGreen,
            secondary = AccentBlue,
            error = OfflineRed,
            background = Color(0xFFFFFFFF),
            surface = Color(0xFFF5F5F5)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
