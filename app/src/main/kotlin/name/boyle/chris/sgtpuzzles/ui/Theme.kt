package name.boyle.chris.sgtpuzzles.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import name.boyle.chris.sgtpuzzles.R

/**
 * The theme for the Compose part of the shell.
 *
 * The palette comes from the same resources as the XML theme:
 * `values/colors_shell.xml` for light and `values-night/colors_shell.xml`
 * for dark. There is one source of truth for both worlds.
 *
 * Dynamic color is not enabled today. The accent is part of the app
 * identity, and a wallpaper-driven palette would put the shell and the
 * game board in different color worlds. If it is added later, it stays a
 * user setting and applies to the shell only, as DESIGN.md requires.
 *
 * The caller passes [darkTheme] instead of letting Compose read the
 * platform configuration. This app handles uiMode changes itself, and
 * the activity's night state is the single source of truth.
 */
@Composable
fun SgTPuzzlesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) darkShellColors() else lightShellColors()
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
