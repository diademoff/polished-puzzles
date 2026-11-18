package name.boyle.chris.sgtpuzzles.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import name.boyle.chris.sgtpuzzles.R

/**
 * The theme for the Compose part of the shell.
 *
 * The palette comes from the same resources as the XML theme:
 * `values/colors_shell.xml` for light and `values-night/colors_shell.xml`
 * for dark. There is one source of truth for both worlds.
 *
 * [dynamicColor] is the user's "Use system colors" setting. On Android 12
 * and later the wallpaper decides the roles; the game board palette does
 * not change, because the board draws its own colours (see
 * `docs/DESIGN.md` section 3). The setting is read by the caller, so a
 * change reaches the screen without a restart.
 *
 * The caller passes [darkTheme] instead of letting Compose read the
 * platform configuration. This app handles uiMode changes itself, and
 * the activity's night state is the single source of truth.
 */
@Composable
fun SgTPuzzlesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkShellColors()
        else -> lightShellColors()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun lightShellColors() = lightColorScheme(
    primary = colorResource(R.color.shell_primary),
    onPrimary = colorResource(R.color.shell_on_primary),
    primaryContainer = colorResource(R.color.shell_primary_container),
    onPrimaryContainer = colorResource(R.color.shell_on_primary_container),
    inversePrimary = colorResource(R.color.shell_inverse_primary),
    secondary = colorResource(R.color.shell_secondary),
    onSecondary = colorResource(R.color.shell_on_secondary),
    secondaryContainer = colorResource(R.color.shell_secondary_container),
    onSecondaryContainer = colorResource(R.color.shell_on_secondary_container),
    tertiary = colorResource(R.color.shell_tertiary),
    onTertiary = colorResource(R.color.shell_on_tertiary),
    tertiaryContainer = colorResource(R.color.shell_tertiary_container),
    onTertiaryContainer = colorResource(R.color.shell_on_tertiary_container),
    background = colorResource(R.color.shell_background),
    onBackground = colorResource(R.color.shell_on_background),
    surface = colorResource(R.color.shell_surface),
    onSurface = colorResource(R.color.shell_on_surface),
    surfaceVariant = colorResource(R.color.shell_surface_variant),
    onSurfaceVariant = colorResource(R.color.shell_on_surface_variant),
    surfaceContainerLowest = colorResource(R.color.shell_surface_container_lowest),
    surfaceContainerLow = colorResource(R.color.shell_surface_container_low),
    surfaceContainer = colorResource(R.color.shell_surface_container),
    surfaceContainerHigh = colorResource(R.color.shell_surface_container_high),
    surfaceContainerHighest = colorResource(R.color.shell_surface_container_highest),
    surfaceDim = colorResource(R.color.shell_surface_dim),
    surfaceBright = colorResource(R.color.shell_surface_bright),
    inverseSurface = colorResource(R.color.shell_inverse_surface),
    inverseOnSurface = colorResource(R.color.shell_inverse_on_surface),
    outline = colorResource(R.color.shell_outline),
    outlineVariant = colorResource(R.color.shell_outline_variant),
    error = colorResource(R.color.shell_error),
    onError = colorResource(R.color.shell_on_error),
    errorContainer = colorResource(R.color.shell_error_container),
    onErrorContainer = colorResource(R.color.shell_on_error_container),
)

@Composable
private fun darkShellColors() = darkColorScheme(
    primary = colorResource(R.color.shell_primary),
    onPrimary = colorResource(R.color.shell_on_primary),
    primaryContainer = colorResource(R.color.shell_primary_container),
    onPrimaryContainer = colorResource(R.color.shell_on_primary_container),
    inversePrimary = colorResource(R.color.shell_inverse_primary),
    secondary = colorResource(R.color.shell_secondary),
    onSecondary = colorResource(R.color.shell_on_secondary),
    secondaryContainer = colorResource(R.color.shell_secondary_container),
    onSecondaryContainer = colorResource(R.color.shell_on_secondary_container),
    tertiary = colorResource(R.color.shell_tertiary),
    onTertiary = colorResource(R.color.shell_on_tertiary),
    tertiaryContainer = colorResource(R.color.shell_tertiary_container),
    onTertiaryContainer = colorResource(R.color.shell_on_tertiary_container),
    background = colorResource(R.color.shell_background),
    onBackground = colorResource(R.color.shell_on_background),
    surface = colorResource(R.color.shell_surface),
    onSurface = colorResource(R.color.shell_on_surface),
    surfaceVariant = colorResource(R.color.shell_surface_variant),
    onSurfaceVariant = colorResource(R.color.shell_on_surface_variant),
    surfaceContainerLowest = colorResource(R.color.shell_surface_container_lowest),
    surfaceContainerLow = colorResource(R.color.shell_surface_container_low),
    surfaceContainer = colorResource(R.color.shell_surface_container),
    surfaceContainerHigh = colorResource(R.color.shell_surface_container_high),
    surfaceContainerHighest = colorResource(R.color.shell_surface_container_highest),
    surfaceDim = colorResource(R.color.shell_surface_dim),
    surfaceBright = colorResource(R.color.shell_surface_bright),
    inverseSurface = colorResource(R.color.shell_inverse_surface),
    inverseOnSurface = colorResource(R.color.shell_inverse_on_surface),
    outline = colorResource(R.color.shell_outline),
    outlineVariant = colorResource(R.color.shell_outline_variant),
    error = colorResource(R.color.shell_error),
    onError = colorResource(R.color.shell_on_error),
    errorContainer = colorResource(R.color.shell_error_container),
    onErrorContainer = colorResource(R.color.shell_on_error_container),
)
