package com.example.bluetoothcommunication

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Accent colors — same in both themes ──────────────────────────────────────
val NeonBlue   = Color(0xFF00D4FF)
val NeonPurple = Color(0xFF7B2FFF)
val SoftCyan   = Color(0xFF00FFD1)

// ── Legacy names — kept so existing files still compile ───────────────────────
val DeepBlack = Color(0xFF050510)
val DarkNavy  = Color(0xFF0A0A2A)
val GlowWhite = Color(0xFFE8F4FF)
val DimWhite  = Color(0xFF8899BB)
val CardBg    = Color(0xFF0D1B3E)

// ── Light color scheme ────────────────────────────────────────────────────────
private val LightColors = lightColorScheme(
    primary            = NeonPurple,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFEDE7FF),
    onPrimaryContainer = Color(0xFF3A0094),
    secondary          = Color(0xFF0099BB),
    onSecondary        = Color.White,
    background         = Color(0xFFF5F5F7),
    onBackground       = Color(0xFF0D0D1A),
    surface            = Color.White,
    onSurface          = Color(0xFF0D0D1A),
    surfaceVariant     = Color(0xFFEBEBF0),
    onSurfaceVariant   = Color(0xFF636380),
    outline            = Color(0xFFDEDEE8),
    outlineVariant     = Color(0xFFEEEEF5),
    error              = Color(0xFFBA1A1A),
    onError            = Color.White,
)

// ── Dark color scheme ─────────────────────────────────────────────────────────
private val DarkColors = darkColorScheme(
    primary            = NeonBlue,
    onPrimary          = Color(0xFF003344),
    primaryContainer   = Color(0xFF004D66),
    onPrimaryContainer = Color(0xFFB3EEFF),
    secondary          = NeonPurple,
    onSecondary        = Color.White,
    background         = Color(0xFF050510),
    onBackground       = Color(0xFFE8F4FF),
    surface            = Color(0xFF0D1130),
    onSurface          = Color(0xFFE8F4FF),
    surfaceVariant     = Color(0xFF0A0A2A),
    onSurfaceVariant   = Color(0xFF8899BB),
    outline            = Color(0xFF243050),
    outlineVariant     = Color(0xFF141E3A),
    error              = Color(0xFFFFB4AB),
    onError            = Color(0xFF690005),
)

@Composable
fun BluetoothChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content  : @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography  = Typography(),
        content     = content
    )
}