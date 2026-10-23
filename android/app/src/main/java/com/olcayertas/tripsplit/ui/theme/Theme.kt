package com.olcayertas.tripsplit.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/**
 * Material 3 theme from the generated tokens (Tokens.kt). Dynamic colour on Android 12 and later, the
 * seed palette otherwise. The extra "positive" roles always come from the tokens, so a positive balance
 * keeps its green role next to dynamic colours.
 */
@Composable
fun TripSplitTheme(dark: Boolean = isSystemInDarkTheme(), dynamic: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(Corner.extraSmall),
        small = RoundedCornerShape(Corner.small),
        medium = RoundedCornerShape(Corner.medium),
        large = RoundedCornerShape(Corner.large),
        extraLarge = RoundedCornerShape(Corner.extraLarge),
    )
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtendedColors else LightExtendedColors) {
        MaterialTheme(colorScheme = colors, typography = TripSplitTypography, shapes = shapes, content = content)
    }
}
