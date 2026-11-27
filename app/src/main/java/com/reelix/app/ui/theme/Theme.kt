package com.reelix.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

private val RxColorScheme = darkColorScheme(
    primary = RxColors.Accent,
    onPrimary = RxColors.Backdrop,
    primaryContainer = RxColors.AccentPressed,
    onPrimaryContainer = RxColors.Backdrop,
    secondary = RxColors.TextSecondary,
    onSecondary = RxColors.Backdrop,
    background = RxColors.Backdrop,
    onBackground = RxColors.TextPrimary,
    surface = RxColors.Surface,
    onSurface = RxColors.TextPrimary,
    surfaceVariant = RxColors.SurfaceElevated,
    onSurfaceVariant = RxColors.TextSecondary,
    outline = RxColors.TextTertiary,
    error = RxColors.Favorite,
)

/**
 * Reelbox 永远深色。影视库在浅色底上像电商，深色底才是影院。
 * 所以忽略 isSystemInDarkTheme()，强制走深色。
 */
@Composable
fun ReelboxTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = RxColorScheme,
        typography = RxTypography,
        content = content
    )
}

/** 给非 Compose 的地方（比如 Media3 PlayerView）用的黑 */
val RxBackdropArgb: Int = RxColors.Backdrop.toArgb()
