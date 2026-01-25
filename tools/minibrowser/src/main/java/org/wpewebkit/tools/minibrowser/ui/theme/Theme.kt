/**
 * Copyright (C) 2026
 *   Author: maceip
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.tools.minibrowser.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Colors from existing color scheme
val Black = Color(0xFF0F0F0F)
val White = Color(0xFFF0F0F0)
val LightGrey05 = Color(0xFFFBFBFE)
val LightGrey10 = Color(0xFFF9F9FB)
val LightGrey20 = Color(0xFFF0F0F4)
val LightGrey30 = Color(0xFFE0E0E6)
val DarkGrey05 = Color(0xFF5B5B66)
val DarkGrey90 = Color(0xFF15141A)
val Violet70 = Color(0xFF592ACB)

private val LightColorScheme = lightColorScheme(
    primary = Violet70,
    onPrimary = White,
    primaryContainer = LightGrey10,
    onPrimaryContainer = Black,
    secondary = Violet70,
    onSecondary = White,
    secondaryContainer = LightGrey20,
    onSecondaryContainer = Black,
    tertiary = Violet70,
    onTertiary = White,
    background = LightGrey05,
    onBackground = Black,
    surface = LightGrey10,
    onSurface = Black,
    surfaceVariant = LightGrey20,
    onSurfaceVariant = DarkGrey05,
    outline = LightGrey30,
    outlineVariant = LightGrey20,
)

private val DarkColorScheme = darkColorScheme(
    primary = Violet70,
    onPrimary = White,
    primaryContainer = DarkGrey90,
    onPrimaryContainer = White,
    secondary = Violet70,
    onSecondary = White,
    secondaryContainer = DarkGrey05,
    onSecondaryContainer = White,
    tertiary = Violet70,
    onTertiary = White,
    background = DarkGrey90,
    onBackground = White,
    surface = DarkGrey90,
    onSurface = White,
    surfaceVariant = DarkGrey05,
    onSurfaceVariant = LightGrey30,
    outline = DarkGrey05,
    outlineVariant = DarkGrey90,
)

@Composable
fun MiniBrowserTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
