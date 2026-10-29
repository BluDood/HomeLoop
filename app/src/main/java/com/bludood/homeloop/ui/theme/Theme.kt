package com.bludood.homeloop.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.bludood.homeloop.data.ThemeChoice

object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 20.dp
    val contentMaxWidth = 600.dp
}

private val LightPalette = lightColorScheme(
    primary = Color(0xFF535F9A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD0D9FC),
    onPrimaryContainer = Color(0xFF222B62),
    secondary = Color(0xFF635A80),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDD5F0),
    onSecondaryContainer = Color(0xFF28223E),
    tertiary = Color(0xFF536889),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE6FA),
    onTertiaryContainer = Color(0xFF1D304B),
    error = Color(0xFFB8293A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE1E6),
    onErrorContainer = Color(0xFF641424),
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF1C1D23),
    surface = Color(0xFFFAFAFC),
    onSurface = Color(0xFF1C1D23),
    surfaceDim = Color(0xFFD8D9E0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F1F6),
    surfaceContainer = Color(0xFFE5E8F0),
    surfaceContainerHigh = Color(0xFFDCE1EC),
    surfaceContainerHighest = Color(0xFFD1D8E5),
    surfaceVariant = Color(0xFFE1E5EE),
    onSurfaceVariant = Color(0xFF484C5C),
    outline = Color(0xFF6F7487),
    outlineVariant = Color(0xFFB9BECC),
    surfaceTint = Color(0xFF535F9A),
    inverseSurface = Color(0xFF2B2C34),
    inverseOnSurface = Color(0xFFF2F2F8),
    inversePrimary = Color(0xFFB6BEFF),
    primaryFixed = Color(0xFFDFE3FF),
    primaryFixedDim = Color(0xFFB6BEFF),
    onPrimaryFixed = Color(0xFF171C41),
    onPrimaryFixedVariant = Color(0xFF3D487C),
    secondaryFixed = Color(0xFFE8E1F6),
    secondaryFixedDim = Color(0xFFC1B9DC),
    onSecondaryFixed = Color(0xFF211B35),
    onSecondaryFixedVariant = Color(0xFF514965),
    tertiaryFixed = Color(0xFFDDE6FA),
    tertiaryFixedDim = Color(0xFFB6C3E5),
    onTertiaryFixed = Color(0xFF15253D),
    onTertiaryFixedVariant = Color(0xFF3E5070),
    scrim = Color(0xFF000000)
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFFB6BEFF),
    onPrimary = Color(0xFF232B63),
    primaryContainer = Color(0xFF353C68),
    onPrimaryContainer = Color(0xFFE0E4FF),
    secondary = Color(0xFFC1B9DC),
    onSecondary = Color(0xFF312D45),
    secondaryContainer = Color(0xFF3B374D),
    onSecondaryContainer = Color(0xFFE6DFF5),
    tertiary = Color(0xFFB6C3E5),
    onTertiary = Color(0xFF27324A),
    tertiaryContainer = Color(0xFF354159),
    onTertiaryContainer = Color(0xFFDFE8FF),
    error = Color(0xFFFF9899),
    onError = Color(0xFF531622),
    errorContainer = Color(0xFF842536),
    onErrorContainer = Color(0xFFFFE3E8),
    background = Color(0xFF15161B),
    onBackground = Color(0xFFE7E8EF),
    surface = Color(0xFF17181D),
    onSurface = Color(0xFFE7E8EF),
    surfaceDim = Color(0xFF15161B),
    surfaceBright = Color(0xFF41424A),
    surfaceContainerLowest = Color(0xFF111216),
    surfaceContainerLow = Color(0xFF1D1F25),
    surfaceContainer = Color(0xFF24262D),
    surfaceContainerHigh = Color(0xFF2D2F38),
    surfaceContainerHighest = Color(0xFF373943),
    surfaceVariant = Color(0xFF343740),
    onSurfaceVariant = Color(0xFFBFC1CE),
    outline = Color(0xFF8B8E9D),
    outlineVariant = Color(0xFF444752),
    surfaceTint = Color(0xFFB6BEFF),
    inverseSurface = Color(0xFFE6E7ED),
    inverseOnSurface = Color(0xFF2A2B33),
    inversePrimary = Color(0xFF535F9A),
    primaryFixed = Color(0xFFDFE3FF),
    primaryFixedDim = Color(0xFFB6BEFF),
    onPrimaryFixed = Color(0xFF171C41),
    onPrimaryFixedVariant = Color(0xFF3D487C),
    secondaryFixed = Color(0xFFE8E1F6),
    secondaryFixedDim = Color(0xFFC1B9DC),
    onSecondaryFixed = Color(0xFF211B35),
    onSecondaryFixedVariant = Color(0xFF514965),
    tertiaryFixed = Color(0xFFDDE6FA),
    tertiaryFixedDim = Color(0xFFB6C3E5),
    onTertiaryFixed = Color(0xFF15253D),
    onTertiaryFixedVariant = Color(0xFF3E5070),
    scrim = Color(0xFF000000)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeLoopTheme(
    theme: ThemeChoice,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val context = LocalContext.current
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    MaterialExpressiveTheme(
        colorScheme = when {
            useDynamic && dark -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            dark -> DarkPalette
            else -> LightPalette
        },
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
