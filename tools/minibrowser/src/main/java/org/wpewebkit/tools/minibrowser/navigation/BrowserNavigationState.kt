/**
 * Copyright (C) 2025 Igalia S.L. <info@igalia.com>
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator

/**
 * Create a navigation state that persists config changes and process death.
 *
 * @param startRoute The top level route to start on. This should also be in `topLevelRoutes`.
 * @param topLevelRoutes The top level routes in the app.
 */
@Composable
fun rememberBrowserNavigationState(
    startRoute: NavKey,
    topLevelRoutes: Set<NavKey>
): BrowserNavigationState {
    // Use rememberSaveable to persist the current top-level route across configuration changes
    val topLevelRoute = rememberSaveable(startRoute, topLevelRoutes) {
        mutableStateOf(startRoute)
    }

    // Create a back stack for each top level route
    val backStacks = topLevelRoutes.associateWith { key -> rememberNavBackStack(key) }

    return remember(startRoute, topLevelRoutes) {
        BrowserNavigationState(
            startRoute = startRoute,
            topLevelRoute = topLevelRoute,
            backStacks = backStacks
        )
    }
}

/**
 * State holder for navigation state. This class does not modify its own state. It is designed
 * to be modified using the [BrowserNavigator] class.
 *
 * @param startRoute The start route. The user will exit the app through this route.
 * @param topLevelRoute The state object that backs the top level route.
 * @param backStacks The back stacks for each top level route.
 */
class BrowserNavigationState(
    val startRoute: NavKey,
    topLevelRoute: MutableState<NavKey>,
    val backStacks: Map<NavKey, NavBackStack<NavKey>>
) {

    /**
     * The current top level route.
     */
    var topLevelRoute: NavKey by topLevelRoute

    /**
     * Convert the navigation state into [NavEntry]s that have been decorated with a
     * [androidx.compose.runtime.saveable.SaveableStateHolder].
     *
     * @param entryProvider The entry provider used to convert the keys in the
     * back stacks to [NavEntry]s.
     */
    @Composable
    fun toDecoratedEntries(
        entryProvider: (NavKey) -> NavEntry<NavKey>
    ): SnapshotStateList<NavEntry<NavKey>> {
        // For each back stack, create a `SaveableStateHolder` decorator and use it to decorate
        // the entries from that stack. When backStacks changes, `rememberDecoratedNavEntries` will
        // be recomposed and a new list of decorated entries is returned.
        val decoratedEntries = backStacks.mapValues { (_, stack) ->
            val decorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
            )
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = decorators,
                entryProvider = entryProvider
            )
        }

        // Only return the entries for the stacks that are currently in use.
        return getTopLevelRoutesInUse()
            .flatMap { decoratedEntries[it] ?: emptyList() }
            .toMutableStateList()
    }

    /**
     * Get the top level routes that are currently in use. The start route is always the first route
     * in the list. This means the user will always exit the app through the starting route
     * ("exit through home" pattern). The list will contain a maximum of one other route.
     *
     * Note that even if a top level route is not in use its state is still retained.
     *
     * @return The current top level routes that are in use.
     */
    private fun getTopLevelRoutesInUse(): List<NavKey> =
        if (topLevelRoute == startRoute) {
            listOf(startRoute)
        } else {
            listOf(startRoute, topLevelRoute)
        }
}

/**
 * Handles navigation events (forward and back) by updating the navigation state.
 */
class BrowserNavigator(private val state: BrowserNavigationState) {
    /**
     * Navigate to a route. If the route is a top-level route, it will switch to that route.
     * Otherwise, it will add the route to the current back stack.
     */
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            // This is a top level route, just switch to it
            state.topLevelRoute = route
        } else {
            state.backStacks[state.topLevelRoute]?.add(route)
        }
    }

    /**
     * Go back in the navigation hierarchy.
     */
    fun goBack() {
        val currentStack = state.backStacks[state.topLevelRoute]
            ?: error("Stack for ${state.topLevelRoute} not found")
        val currentRoute = currentStack.last()

        // If we're at the base of the current route, go back to the start route stack.
        if (currentRoute == state.topLevelRoute) {
            state.topLevelRoute = state.startRoute
        } else {
            currentStack.removeLastOrNull()
        }
    }
}
