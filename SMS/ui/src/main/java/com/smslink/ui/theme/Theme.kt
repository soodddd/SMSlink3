package com.smslink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = OnPrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryLight,
    onSecondaryContainer = OnSecondary,
    tertiary = Info,
    error = Error,
    errorContainer = Error.copy(alpha = 0.1f),
    onError = OnPrimary,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = OnSurfaceVariant.copy(alpha = 0.3f),
    outlineVariant = OnSurfaceVariant.copy(alpha = 0.1f)
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryVariant,
    onPrimaryContainer = OnPrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryVariant,
    onSecondaryContainer = OnSecondary,
    tertiary = Info,
    error = Error,
    errorContainer = Error.copy(alpha = 0.2f),
    onError = OnPrimary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurface.copy(alpha = 0.8f),
    onSurfaceVariant = DarkOnSurface.copy(alpha = 0.7f),
    outline = DarkOnSurface.copy(alpha = 0.3f),
    outlineVariant = DarkOnSurface.copy(alpha = 0.1f)
)

@Composable
fun SmsLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
