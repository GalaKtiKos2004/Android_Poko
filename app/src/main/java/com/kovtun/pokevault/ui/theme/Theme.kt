package com.kovtun.pokevault.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFFC2182F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD8),
    onPrimaryContainer = Color(0xFF410007),
    secondary = Color(0xFF2D5D8C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF001C38),
    tertiary = Color(0xFF7A5900),
    tertiaryContainer = Color(0xFFFFDEA1),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB3B0),
    onPrimary = Color(0xFF680012),
    primaryContainer = Color(0xFF92001E),
    onPrimaryContainer = Color(0xFFFFDAD8),
    secondary = Color(0xFFA2C9FF),
    onSecondary = Color(0xFF00315B),
    secondaryContainer = Color(0xFF094A80),
    onSecondaryContainer = Color(0xFFD3E4FF),
    tertiary = Color(0xFFF7BE3B),
    tertiaryContainer = Color(0xFF5C4200),
    background = Color(0xFF1A1111),
    surface = Color(0xFF1A1111),
)

/** Accent colours for local profiles (avatar circles). */
val ProfileColors = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047),
    Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00897B),
)

fun typeColor(type: String): Color = when (type) {
    "fire" -> Color(0xFFEE8130)
    "water" -> Color(0xFF6390F0)
    "grass" -> Color(0xFF7AC74C)
    "electric" -> Color(0xFFF7D02C)
    "ice" -> Color(0xFF96D9D6)
    "fighting" -> Color(0xFFC22E28)
    "poison" -> Color(0xFFA33EA1)
    "ground" -> Color(0xFFE2BF65)
    "flying" -> Color(0xFFA98FF3)
    "psychic" -> Color(0xFFF95587)
    "bug" -> Color(0xFFA6B91A)
    "rock" -> Color(0xFFB6A136)
    "ghost" -> Color(0xFF735797)
    "dragon" -> Color(0xFF6F35FC)
    "dark" -> Color(0xFF705746)
    "steel" -> Color(0xFFB7B7CE)
    "fairy" -> Color(0xFFD685AD)
    else -> Color(0xFFA8A77A)
}

@Composable
fun PokeVaultTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
