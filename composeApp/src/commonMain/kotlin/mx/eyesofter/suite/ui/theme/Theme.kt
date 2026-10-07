// Proveedor del tema visual EyesofterTheme con soporte para modo claro y modo oscuro en Material 3.
// Depende de mx.eyesofter.suite.ui.theme.Color y mx.eyesofter.suite.ui.theme.Type.
package mx.eyesofter.suite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    secondary = NaranjaWarning,
    onSecondary = Color.White,
    background = SurfaceBackground,
    onBackground = OnSurfaceText,
    surface = Color.White,
    onSurface = OnSurfaceText
)

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    secondary = NaranjaWarning,
    onSecondary = Color.White,
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF8FAFC)
)

@Composable
fun EyesofterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
