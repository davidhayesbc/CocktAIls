package com.davidhayesbc.cocktails.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Juniper = Color(0xFF285E4D)
private val Paper = Color(0xFFF5F1E8)
private val Surface = Color(0xFFFFFDFA)
private val Ink = Color(0xFF202923)
private val MutedInk = Color(0xFF59645D)
private val Citrus = Color(0xFFD78A32)
private val DarkCanvas = Color(0xFF171D19)
private val DarkSurface = Color(0xFF242C26)
private val DarkSurfaceVariant = Color(0xFF303A32)
private val DarkInk = Color(0xFFF3F0E8)

private val LightColors = lightColorScheme(
    primary = Juniper,
    onPrimary = Color.White,
    secondary = Citrus,
    onSecondary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    onSurfaceVariant = MutedInk,
    secondaryContainer = Color(0xFFE7EDE3),
    onSecondaryContainer = Ink,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BC9AE),
    onPrimary = DarkCanvas,
    secondary = Color(0xFFE8B66F),
    background = DarkCanvas,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkInk,
    onSurface = DarkInk,
    onSurfaceVariant = Color(0xFFC1C8C0),
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun CocktailsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
    ) {
        SideEffect {
            context.findActivity()?.window?.let { window ->
                val systemBarColor = (if (darkTheme) DarkCanvas else Paper).toArgb()
                window.statusBarColor = systemBarColor
                window.navigationBarColor = systemBarColor
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}
