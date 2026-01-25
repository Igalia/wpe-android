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

package org.wpewebkit.tools.minibrowser.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.navigation3.ui.NavDisplay

import org.wpewebkit.tools.minibrowser.BrowserViewModel
import org.wpewebkit.tools.minibrowser.INITIAL_URL
import org.wpewebkit.tools.minibrowser.navigation.BottomSheetSceneStrategy
import org.wpewebkit.tools.minibrowser.navigation.BrowserNavigator
import org.wpewebkit.tools.minibrowser.navigation.CompositeSceneStrategy
import org.wpewebkit.tools.minibrowser.navigation.Settings
import org.wpewebkit.tools.minibrowser.navigation.TabDetail
import org.wpewebkit.tools.minibrowser.navigation.TabList
import org.wpewebkit.tools.minibrowser.navigation.TabSheet
import org.wpewebkit.tools.minibrowser.navigation.rememberBrowserNavigationState
import org.wpewebkit.tools.minibrowser.ui.components.EmptyDetailPlaceholder
import org.wpewebkit.tools.minibrowser.ui.components.TabListPane
import org.wpewebkit.tools.minibrowser.ui.components.WebViewPane

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val browserState by viewModel.browserState.collectAsState()
    val tabs = browserState.tabs

    val tabRoutes = remember(tabs) { tabs.map { TabDetail(it.id) }.toSet() }
    val topLevelRoutes = remember(tabRoutes) { tabRoutes + TabList }

    val navigationState = rememberBrowserNavigationState(
        startRoute = TabList,
        topLevelRoutes = topLevelRoutes
    )
    val navigator = remember(navigationState) { BrowserNavigator(navigationState) }

    LaunchedEffect(Unit) {
        if (viewModel.browserState.value.tabs.isEmpty()) {
            val tab = viewModel.createTab(context, INITIAL_URL)
            navigator.navigate(TabDetail(tab.id))
        }
    }

    LaunchedEffect(navigationState.topLevelRoute) {
        val currentRoute = navigationState.topLevelRoute
        if (currentRoute is TabDetail) {
            viewModel.selectTab(currentRoute.tabId)
        }
    }

    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val directive = remember(windowAdaptiveInfo) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(horizontalPartitionSpacerSize = 0.dp)
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(directive = directive)

    val bottomSheetStrategy = remember { BottomSheetSceneStrategy<NavKey>() }
    val sceneStrategy = remember(bottomSheetStrategy, listDetailStrategy) {
        CompositeSceneStrategy(listOf(bottomSheetStrategy, listDetailStrategy))
    }

    val entryProvider = entryProvider {
        entry<TabList>(
            metadata = ListDetailSceneStrategy.listPane(
                detailPlaceholder = {
                    EmptyDetailPlaceholder(
                        onNewTab = {
                            val tab = viewModel.createTab(context, INITIAL_URL)
                            navigator.navigate(TabDetail(tab.id))
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
                    navigator.navigate(TabDetail(tab.id))
                },
                onTabClose = { tab ->
                    viewModel.removeTab(tab.id)
                    val currentRoute = navigationState.topLevelRoute
                    if (currentRoute is TabDetail && currentRoute.tabId == tab.id) {
                        navigator.goBack()
                        browserState.selectedTabId?.let { newSelectedId ->
                            navigator.navigate(TabDetail(newSelectedId))
                        }
                    }
                },
                onNewTab = {
                    val tab = viewModel.createTab(context, INITIAL_URL)
                    navigator.navigate(TabDetail(tab.id))
                },
                onMoveTab = { fromIndex, toIndex ->
                    viewModel.moveTab(fromIndex, toIndex)
                }
            )
        }

        entry<TabDetail>(
            metadata = ListDetailSceneStrategy.detailPane()
        ) { tabDetail ->
            val tab = viewModel.findTab(tabDetail.tabId)
            if (tab != null) {
                WebViewPane(
                    tab = tab,
                    onSettingsClick = onNavigateToSettings,
                    showTabsButton = true,
                    onTabsClick = {
                        navigator.goBack()
                    }
                )
            } else {
                EmptyDetailPlaceholder(
                    onNewTab = {
                        val newTab = viewModel.createTab(context, INITIAL_URL)
                        navigator.navigate(TabDetail(newTab.id))
                    }
                )
            }
        }

        entry<Settings>(
            metadata = ListDetailSceneStrategy.extraPane()
        ) {
            SettingsPlaceholder()
        }

        entry<TabSheet>(
            metadata = BottomSheetSceneStrategy.bottomSheet()
        ) { sheet ->
            val tab = viewModel.findTab(sheet.tabId)
            TabActionsSheet(
                title = tab?.url ?: "Tab actions",
                onReload = {
                    tab?.reload()
                    navigator.goBack()
                },
                onDismiss = {
                    navigator.goBack()
                }
            )
        }
    }

    NavDisplay(
        entries = navigationState.toDecoratedEntries(entryProvider),
        onBack = {
            val currentRoute = navigationState.topLevelRoute
            if (currentRoute is TabDetail) {
                val tab = viewModel.findTab(currentRoute.tabId)
                if (tab?.canGoBack == true) {
                    tab.goBack()
                    return@NavDisplay
                }
            }
            navigator.goBack()
        },
        sceneStrategy = sceneStrategy,
        transitionSpec = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(320)
            ) togetherWith slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(320)
            )
        },
        popTransitionSpec = {
            slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(280)
            ) togetherWith slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(280)
            )
        },
        predictivePopTransitionSpec = {
            slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(280)
            ) togetherWith slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(280)
            )
        }
    )
}

@Composable
private fun TabActionsSheet(
    title: String,
    onReload: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Tab actions",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onReload) {
                Text(text = "Reload")
            }
            Button(onClick = onDismiss) {
                Text(text = "Close")
            }
        }
    }
}

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
