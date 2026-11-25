package `fun`.tryliner.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LinerDarkColorScheme = darkColorScheme(
    primary = LinerColors.AccentPrimary,
    onPrimary = LinerColors.BtnPrimaryText,
    secondary = LinerColors.AccentSecondary,
    onSecondary = LinerColors.BtnPrimaryText,
    background = LinerColors.AmbientBase,
    onBackground = LinerColors.TextPrimary,
    surface = LinerColors.SurfaceGlass,
    onSurface = LinerColors.TextPrimary,
    surfaceVariant = LinerColors.BgPanel,
    onSurfaceVariant = LinerColors.TextSecondary,
    surfaceContainerHigh = LinerColors.BgElevated,
    outline = Color.Transparent,
    outlineVariant = LinerColors.BorderAlpha20,
    error = LinerColors.AccentWarning,
    onError = LinerColors.BtnPrimaryText
)

@Composable
fun LinerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LinerDarkColorScheme,
        typography = LinerTypography,
        shapes = LinerMaterialShapes,
        content = content
    )
}
