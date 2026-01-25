/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
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

package org.wpewebkit.tools.minibrowser.ui.components

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.wpewebkit.tools.minibrowser.SEARCH_URI_BASE
import org.wpewebkit.tools.minibrowser.Tab
import org.wpewebkit.tools.minibrowser.normalizeAddress

/**
 * A composable that displays a WPEWebView with navigation controls.
 * This is used as the "detail" pane in the ListDetailSceneStrategy.
 *
 * @param tab The tab containing the WPEWebView to display
 * @param onSettingsClick Callback when settings is clicked
 * @param showTabsButton Whether to show the tabs button (on small screens)
 * @param onTabsClick Callback when tabs button is clicked
 * @param modifier Modifier for this composable
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewPane(
    tab: Tab,
    onSettingsClick: () -> Unit,
    showTabsButton: Boolean = false,
    onTabsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var urlInputText by remember(tab.id) { mutableStateOf(tab.url) }
    var isUrlFocused by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // Update URL text when tab URL changes (unless user is editing)
    if (!isUrlFocused && urlInputText != tab.url) {
        urlInputText = tab.url
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) {
        // WebView content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                factory = { _ ->
                    // Detach from any existing parent before attaching to new parent
                    (tab.webview.parent as? ViewGroup)?.removeView(tab.webview)
                    tab.webview.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { view ->
                    // Re-attach if the view was detached
                    if (view != tab.webview) {
                        (view.parent as? ViewGroup)?.removeView(view)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Progress bar
        if (tab.isLoading) {
            LinearProgressIndicator(
                progress = { tab.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        // Bottom toolbar with URL bar and navigation
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // URL input field
            OutlinedTextField(
                value = urlInputText,
                onValueChange = { urlInputText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        isUrlFocused = focusState.isFocused
                        if (focusState.isFocused) {
                            // Select all text when focused
                        }
                    },
                placeholder = {
                    Text("Search or enter URL")
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val url = processUrlInput(urlInputText)
                        tab.loadUrl(url)
                        focusManager.clearFocus()
                    }
                ),
                trailingIcon = if (tab.isLoading) {
                    {
                        IconButton(onClick = { tab.stopLoading() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Stop loading"
                            )
                        }
                    }
                } else null
            )

            // Navigation buttons row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { tab.goBack() },
                    enabled = tab.canGoBack
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Go Back",
                        tint = if (tab.canGoBack) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }

                IconButton(
                    onClick = { tab.goForward() },
                    enabled = tab.canGoForward
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Go Forward",
                        tint = if (tab.canGoForward) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }

                IconButton(onClick = { tab.reload() }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload"
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Show tabs count button on small screens
                if (showTabsButton) {
                    TabCountButton(
                        count = 1, // Will be provided by parent
                        onClick = onTabsClick
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Menu button
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options"
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                showMenu = false
                                onSettingsClick()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    // Cleanup when tab changes
    DisposableEffect(tab.id) {
        onDispose {
            // Cleanup if needed
        }
    }
}

/**
 * Process URL input - normalize URLs or convert to search query.
 */
private fun processUrlInput(text: String): String {
    val trimmed = text.trim()
    return if ((trimmed.contains(".") || trimmed.contains(":")) && !trimmed.contains(" ")) {
        normalizeAddress(trimmed)
    } else {
        SEARCH_URI_BASE + trimmed
    }
}

/**
 * A button showing the tab count, used on small screens.
 */
@Composable
private fun TabCountButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Placeholder content shown when no tab is selected.
 */
@Composable
fun EmptyDetailPlaceholder(
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Select a tab or create a new one",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
