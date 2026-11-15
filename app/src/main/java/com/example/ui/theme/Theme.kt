package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Immutable
data class DingExtendedColors(
    val outgoingBubble: Color,
    val incomingBubble: Color,
    val outgoingBubbleText: Color,
    val incomingBubbleText: Color,
    val wallpaperBackground: Color,
    val wallpaperPattern: Color,
    val readReceiptBlue: Color,
    val onlineGreen: Color,
    val securityBannerBg: Color,
    val securityBannerText: Color,
    val subtleDivider: Color
)

private val LightExtendedColors = DingExtendedColors(
    outgoingBubble = OutgoingBubbleLight,
    incomingBubble = IncomingBubbleLight,
    outgoingBubbleText = LightOnSurface,
    incomingBubbleText = LightOnSurface,
    wallpaperBackground = ChatWallpaperLight,
    wallpaperPattern = ChatWallpaperPatternLight,
    readReceiptBlue = ReadReceiptBlue,
    onlineGreen = OnlineGreen,
    securityBannerBg = SecurityBannerLight,
    securityBannerText = SecurityBannerTextLight,
    subtleDivider = LightOutline
)

private val DarkExtendedColors = DingExtendedColors(
    outgoingBubble = OutgoingBubbleDark,
    incomingBubble = IncomingBubbleDark,
    outgoingBubbleText = DarkOnSurface,
    incomingBubbleText = DarkOnSurface,
    wallpaperBackground = ChatWallpaperDark,
    wallpaperPattern = ChatWallpaperPatternDark,
    readReceiptBlue = ReadReceiptBlue,
    onlineGreen = OnlineGreen,
    securityBannerBg = SecurityBannerDark,
    securityBannerText = SecurityBannerTextDark,
    subtleDivider = DarkOutline
)

val LocalDingColors = staticCompositionLocalOf { LightExtendedColors }

private val DarkColorScheme = darkColorScheme(
    primary = MintAccent,
    onPrimary = Color(0xFF002117),
    primaryContainer = EmeraldPrimary,
    onPrimaryContainer = MintLight,
    secondary = TealSecondary,
    onSecondary = Color.White,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = DarkOnSurface,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DangerRedDark
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color.White,
    primaryContainer = MintContainer,
    onPrimaryContainer = EmeraldPrimaryDark,
    secondary = MintAccent,
    onSecondary = Color.White,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightOnSurface,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = DangerRed
)

@Composable
fun DingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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

    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalDingColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DingTypography,
            content = content
        )
    }
}
