package com.example.flightlog.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val Lime = Color(0xFFB7F34A)
val Amber = Color(0xFFFFB84D)
val TrailCyan = Color(0xFF42D9E8)
val Night = Color(0xFF101512)
val Charcoal = Color(0xFF18201B)
val SoftWhite = Color(0xFFF2F6F1)

private val DarkColors = darkColorScheme(
    primary = Lime,
    onPrimary = Color(0xFF162000),
    secondary = TrailCyan,
    tertiary = Amber,
    background = Night,
    onBackground = SoftWhite,
    surface = Charcoal,
    onSurface = SoftWhite,
    surfaceVariant = Color(0xFF253129),
    onSurfaceVariant = Color(0xFFC0CABF),
    error = Color(0xFFFF6B63),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF426900),
    onPrimary = Color.White,
    secondary = Color(0xFF006874),
    tertiary = Color(0xFF855400),
    background = Color(0xFFF7FBF5),
    onBackground = Color(0xFF171D17),
    surface = Color.White,
    onSurface = Color(0xFF171D17),
    surfaceVariant = Color(0xFFE1E9DF),
    onSurfaceVariant = Color(0xFF414940),
)

@Composable
fun FlightLogTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
