package it.bosiux.camera4u.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The warm film palette, shared with Film Simulator.
private val LightColors = lightColorScheme(
    primary = Color(0xFF745B0C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE08F),
    onPrimaryContainer = Color(0xFF241A00),
    secondary = Color(0xFF695D3F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2E1BB),
    onSecondaryContainer = Color(0xFF231B04),
    tertiary = Color(0xFF47664A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC9ECC8),
    onTertiaryContainer = Color(0xFF03210B),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFFFF8F1),
    onBackground = Color(0xFF1F1B13),
    surface = Color(0xFFFFF8F1),
    onSurface = Color(0xFF1F1B13),
    surfaceVariant = Color(0xFFEDE1CF),
    onSurfaceVariant = Color(0xFF4C4639),
    outline = Color(0xFF7E7667),
    outlineVariant = Color(0xFFCFC5B4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF2E5),
    surfaceContainer = Color(0xFFF6EDDF),
    surfaceContainerHigh = Color(0xFFF0E7D9),
    surfaceContainerHighest = Color(0xFFEAE1D4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE4C36C),
    onPrimary = Color(0xFF3D2E00),
    primaryContainer = Color(0xFF584400),
    onPrimaryContainer = Color(0xFFFFE08F),
    secondary = Color(0xFFD5C5A0),
    onSecondary = Color(0xFF392F15),
    secondaryContainer = Color(0xFF51462A),
    onSecondaryContainer = Color(0xFFF2E1BB),
    tertiary = Color(0xFFADCFAD),
    onTertiary = Color(0xFF19361F),
    tertiaryContainer = Color(0xFF304D33),
    onTertiaryContainer = Color(0xFFC9ECC8),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF16130B),
    onBackground = Color(0xFFEAE1D4),
    surface = Color(0xFF16130B),
    onSurface = Color(0xFFEAE1D4),
    surfaceVariant = Color(0xFF4C4639),
    onSurfaceVariant = Color(0xFFCFC5B4),
    outline = Color(0xFF989080),
    outlineVariant = Color(0xFF4C4639),
    surfaceContainerLowest = Color(0xFF110E07),
    surfaceContainerLow = Color(0xFF1F1B13),
    surfaceContainer = Color(0xFF231F17),
    surfaceContainerHigh = Color(0xFF2E2A21),
    surfaceContainerHighest = Color(0xFF39342B),
)

@Composable
fun FilmCameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
