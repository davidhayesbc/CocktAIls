package com.davidhayesbc.cocktails.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Juniper = Color(0xFF285E4D)
private val Paper = Color(0xFFF5F1E8)
private val Surface = Color(0xFFFFFDFA)
private val Ink = Color(0xFF202923)
private val MutedInk = Color(0xFF59645D)
private val Citrus = Color(0xFFD78A32)
private val DarkCanvas = Color(0xFF171D19)
private val DarkSurface = Color(0xFF242C26)
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
    secondary = Color(0xFFE8B66F),
    background = DarkCanvas,
    surface = DarkSurface,
    onBackground = DarkInk,
    onSurface = DarkInk,
    onSurfaceVariant = Color(0xFFC1C8C0),
)

@Composable
fun CocktailsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
