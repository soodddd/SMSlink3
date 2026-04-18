package com.smslink.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 深色主题配色方案 - 暖色系
 */
private val DarkColorScheme = darkColorScheme(
    primary = WarmOrangeDark,
    onPrimary = BackgroundDark,
    primaryContainer = WarmOrangeDark.copy(alpha = 0.3f),
    onPrimaryContainer = WarmOrangeDark,

    secondary = AmberAccentDark,
    onSecondary = BackgroundDark,
    secondaryContainer = AmberAccentDark.copy(alpha = 0.3f),
    onSecondaryContainer = AmberAccentDark,

    tertiary = WarmGoldDark,
    onTertiary = BackgroundDark,
    tertiaryContainer = WarmGoldDark.copy(alpha = 0.3f),
    onTertiaryContainer = WarmGoldDark,

    error = WarmRedDark,
    onError = BackgroundDark,
    errorContainer = WarmRedDark.copy(alpha = 0.3f),
    onErrorContainer = WarmRedDark,

    background = BackgroundDark,
    onBackground = SurfaceLight,
    surface = SurfaceDark,
    onSurface = SurfaceLight,
    surfaceVariant = SurfaceDark.copy(alpha = 0.8f),
    onSurfaceVariant = SurfaceLight.copy(alpha = 0.7f),

    outline = WarmBlueGrayDark,
    outlineVariant = WarmBlueGrayDark.copy(alpha = 0.5f)
)

/**
 * 浅色主题配色方案 - 暖色系
 */
private val LightColorScheme = lightColorScheme(
    primary = WarmOrange,
    onPrimary = SurfaceLight,
    primaryContainer = WarmOrange.copy(alpha = 0.1f),
    onPrimaryContainer = WarmOrange,

    secondary = AmberAccent,
    onSecondary = BackgroundDark,
    secondaryContainer = AmberAccent.copy(alpha = 0.1f),
    onSecondaryContainer = AmberAccent,

    tertiary = WarmGold,
    onTertiary = BackgroundDark,
    tertiaryContainer = WarmGold.copy(alpha = 0.1f),
    onTertiaryContainer = WarmGold,

    error = WarmRed,
    onError = SurfaceLight,
    errorContainer = WarmRed.copy(alpha = 0.1f),
    onErrorContainer = WarmRed,

    background = BackgroundLight,
    onBackground = BackgroundDark,
    surface = SurfaceLight,
    onSurface = BackgroundDark,
    surfaceVariant = BackgroundLight.copy(alpha = 0.5f),
    onSurfaceVariant = BackgroundDark.copy(alpha = 0.6f),

    outline = WarmBlueGray,
    outlineVariant = WarmBlueGray.copy(alpha = 0.5f)
)

/**
 * SMS-link 主题
 * 支持浅色和深色模式，采用暖色系配色
 */
@Composable
fun SmsLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // 默认关闭动态颜色，使用自定义暖色系
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
