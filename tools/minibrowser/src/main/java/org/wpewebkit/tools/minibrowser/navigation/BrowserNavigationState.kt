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

package org.wpewebkit.tools.minibrowser.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * Creates and remembers a [BrowserNavigationState] for managing browser navigation.
 *
 * This function uses a single [NavBackStack] for all navigation, with state properly
 * saved across configuration changes via [rememberNavBackStack].
 *
 * @param startRoute The initial route to display (typically TabList)
 * @return A stable [BrowserNavigationState] instance
 */
@Composable
fun rememberBrowserNavigationState(
    startRoute: NavKey = TabList
): BrowserNavigationState {
    val backStack = rememberNavBackStack(startRoute)

    return remember(backStack) {
        BrowserNavigationState(backStack)
    }
}

/**
 * Manages navigation state for the browser using a single [NavBackStack].
 *
 * Tab selection is handled separately by the ViewModel - this only manages
 * the navigation structure (list -> detail -> settings/sheets).
 */
@Stable
class BrowserNavigationState(
    val backStack: NavBackStack<NavKey>
) {
    /**
     * The current route at the top of the back stack.
     */
    val currentRoute: NavKey?
        get() = backStack.lastOrNull()

    /**
     * Whether we're currently showing a detail view (TabDetail).
     */
    val isShowingDetail: Boolean
        get() = currentRoute is TabDetail

    /**
     * Gets the currently displayed tab ID, if showing a tab detail.
     */
    val currentTabId: String?
        get() = (currentRoute as? TabDetail)?.tabId
}

/**
 * Navigator for browser navigation actions.
 *
 * This follows Navigation 3's simple pattern where navigation is just
 * adding/removing from a single back stack.
 */
@Stable
class BrowserNavigator(private val state: BrowserNavigationState) {

    /**
     * Navigate to a route. For TabDetail, replaces any existing detail
     * to avoid building up a large stack of tab views.
     */
    fun navigate(route: NavKey) {
        when (route) {
            is TabDetail -> {
                // If we're already on a TabDetail, replace it instead of stacking
                val current = state.currentRoute
                if (current is TabDetail) {
                    state.backStack.removeLastOrNull()
                }
                state.backStack.add(route)
            }
            is TabSheet, is Settings -> {
                // Overlay routes just push on top
                state.backStack.add(route)
            }
            is TabList -> {
                // Going to TabList means popping back to root
                while (state.backStack.size > 1) {
                    state.backStack.removeLastOrNull()
                }
            }
            else -> {
                state.backStack.add(route)
            }
        }
    }

    /**
     * Navigate to a tab, updating the back stack appropriately.
     */
    fun navigateToTab(tabId: String) {
        navigate(TabDetail(tabId))
    }

    /**
     * Go back in the navigation stack.
     * @return true if back was handled, false if at root
     */
    fun goBack(): Boolean {
        if (state.backStack.size <= 1) {
            return false
        }
        state.backStack.removeLastOrNull()
        return true
    }

    /**
     * Check if we can go back in navigation (not counting in-page back).
     */
    fun canGoBack(): Boolean = state.backStack.size > 1
}
