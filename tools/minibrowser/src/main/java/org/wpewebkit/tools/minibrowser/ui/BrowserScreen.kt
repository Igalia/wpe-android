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

package org.wpewebkit.tools.minibrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import org.wpewebkit.tools.minibrowser.BrowserViewModel
import org.wpewebkit.tools.minibrowser.INITIAL_URL
import org.wpewebkit.tools.minibrowser.navigation.Settings
import org.wpewebkit.tools.minibrowser.navigation.TabDetail
import org.wpewebkit.tools.minibrowser.navigation.TabList
import org.wpewebkit.tools.minibrowser.ui.components.EmptyDetailPlaceholder
import org.wpewebkit.tools.minibrowser.ui.components.TabListPane
import org.wpewebkit.tools.minibrowser.ui.components.WebViewPane

/**
 * The main browser screen using Material3 Adaptive Navigation3.
 *
 * Uses ListDetailSceneStrategy where:
 * - List pane: Shows the tab list (visible on larger screens)
 * - Detail pane: Shows the active WPEWebView
 * - Extra pane: Shows settings (on largest screens)
 *
 * On small screens, only the detail pane (web view) is shown by default.
 * Users can navigate to the tab list through a tabs button.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val browserState by viewModel.browserState.collectAsState()

    // Create the back stack starting with TabList
    val backStack = rememberNavBackStack(TabList)

    // Initialize with a default tab if none exists (using LaunchedEffect for side effect)
    LaunchedEffect(Unit) {
        if (viewModel.browserState.value.tabs.isEmpty()) {
            val tab = viewModel.createTab(context, INITIAL_URL)
            backStack.add(TabDetail(tab.id))
        }
    }

    // When selected tab changes, update back stack
    val selectedTabId = browserState.selectedTabId
    LaunchedEffect(selectedTabId) {
        if (selectedTabId != null) {
            val currentDetail = backStack.lastOrNull() as? TabDetail
            if (currentDetail?.tabId != selectedTabId) {
                // Remove old detail entries
                while (backStack.lastOrNull() is TabDetail) {
                    backStack.removeLastOrNull()
                }
                backStack.add(TabDetail(selectedTabId))
            }
        }
    }

    // Configure adaptive layout with no gap between panes
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val directive = remember(windowAdaptiveInfo) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)

    NavDisplay(
        backStack = backStack,
        onBack = {
            // Handle back navigation
            val currentEntry = backStack.lastOrNull()
            when (currentEntry) {
                is TabDetail -> {
                    // Try to go back in the webview's history first
                    val tab = viewModel.findTab(currentEntry.tabId)
                    if (tab?.canGoBack == true) {
                        tab.goBack()
                    } else {
                        backStack.removeLastOrNull()
                    }
                }
                else -> backStack.removeLastOrNull()
            }
        },
        sceneStrategy = listDetailStrategy,
        entryProvider = entryProvider {
            // List pane entry - shows all tabs
            entry<TabList>(
                metadata = ListDetailSceneStrategy.listPane(
                    detailPlaceholder = {
                        EmptyDetailPlaceholder(
                            onNewTab = {
                                val tab = viewModel.createTab(context, INITIAL_URL)
                                backStack.add(TabDetail(tab.id))
                            }
                        )
                    }
                )
            ) {
                TabListPane(
                    tabs = browserState.tabs,
                    selectedTabId = browserState.selectedTabId,
                    onTabClick = { tab ->
                        viewModel.selectTab(tab.id)
                        backStack.add(TabDetail(tab.id))
                    },
                    onTabClose = { tab ->
                        viewModel.removeTab(tab.id)
                        // If we were viewing this tab, go back
                        if (backStack.lastOrNull() == TabDetail(tab.id)) {
                            backStack.removeLastOrNull()
                            // If there's another tab, navigate to it
                            browserState.selectedTabId?.let { newSelectedId ->
                                backStack.add(TabDetail(newSelectedId))
                            }
                        }
                    },
                    onNewTab = {
                        val tab = viewModel.createTab(context, INITIAL_URL)
                        backStack.add(TabDetail(tab.id))
                    },
                    onMoveTab = { fromIndex, toIndex ->
                        viewModel.moveTab(fromIndex, toIndex)
                    }
                )
            }

            // Detail pane entry - shows the active web view
            entry<TabDetail>(
                metadata = ListDetailSceneStrategy.detailPane()
            ) { tabDetail ->
                val tab = viewModel.findTab(tabDetail.tabId)
                if (tab != null) {
                    WebViewPane(
                        tab = tab,
                        onSettingsClick = onNavigateToSettings,
                        showTabsButton = true, // Show on small screens
                        onTabsClick = {
                            // Navigate to tab list
                            while (backStack.lastOrNull() is TabDetail) {
                                backStack.removeLastOrNull()
                            }
                        }
                    )
                } else {
                    // Tab was closed, show placeholder
                    EmptyDetailPlaceholder(
                        onNewTab = {
                            val newTab = viewModel.createTab(context, INITIAL_URL)
                            backStack.add(TabDetail(newTab.id))
                        }
                    )
                }
            }

            // Settings pane entry - extra pane on large screens
            entry<Settings>(
                metadata = ListDetailSceneStrategy.extraPane()
            ) {
                SettingsPlaceholder()
            }
        }
    )
}

/**
 * Temporary settings placeholder - will be replaced with actual settings.
 */
@Composable
private fun SettingsPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Coming soon",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
