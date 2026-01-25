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

package org.wpewebkit.tools.minibrowser

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val INITIAL_URL = "https://igalia.com"
const val SEARCH_URI_BASE = "https://duckduckgo.com/?q="

@Stable
data class BrowserState(
    val tabs: List<Tab> = emptyList(),
    val selectedTabId: String? = null,
) {
    val selectedTab: Tab?
        get() = tabs.find { it.id == selectedTabId }

    val tabCount: Int
        get() = tabs.size
}

class BrowserViewModel : ViewModel() {
    private val _browserState = MutableStateFlow(BrowserState())
    val browserState = _browserState.asStateFlow()

    /**
     * Adds a new tab and optionally selects it.
     */
    fun addTab(tab: Tab, select: Boolean = true) {
        _browserState.update {
            it.copy(
                tabs = it.tabs + tab,
                selectedTabId = if (select) tab.id else it.selectedTabId
            )
        }
    }

    /**
     * Creates a new tab with the given URL and adds it to the browser.
     */
    fun createTab(context: Context, url: String = INITIAL_URL): Tab {
        val tab = Tab.newTab(context, url)
        addTab(tab)
        return tab
    }

    /**
     * Selects a tab by its ID.
     */
    fun selectTab(tabId: String) {
        _browserState.update {
            it.copy(selectedTabId = tabId)
        }
    }

    /**
     * Removes a tab by its ID.
     * If the removed tab was selected, selects the next available tab.
     */
    fun removeTab(tabId: String) {
        _browserState.update { state ->
            val newTabs = state.tabs.filter { it.id != tabId }
            val newSelectedId = when {
                // If the removed tab wasn't selected, keep current selection
                state.selectedTabId != tabId -> state.selectedTabId
                // If there are no tabs left, no selection
                newTabs.isEmpty() -> null
                // Otherwise, select the first tab
                else -> {
                    val removedIndex = state.tabs.indexOfFirst { it.id == tabId }
                    val newIndex = minOf(removedIndex, newTabs.lastIndex)
                    newTabs.getOrNull(newIndex)?.id
                }
            }
            state.copy(
                tabs = newTabs,
                selectedTabId = newSelectedId
            )
        }
    }

    /**
     * Closes all tabs.
     */
    fun closeAllTabs() {
        _browserState.update {
            it.copy(tabs = emptyList(), selectedTabId = null)
        }
    }

    /**
     * Finds a tab by its ID, or null if not found.
     */
    fun findTab(id: String): Tab? {
        return browserState.value.tabs.find { tab -> tab.id == id }
    }

    /**
     * Finds a tab by its ID, throws if not found.
     */
    fun getTab(id: String): Tab {
        return browserState.value.tabs.first { tab -> tab.id == id }
    }

    /**
     * Gets the currently selected tab, or null if none selected.
     */
    fun selectedTab(): Tab? {
        return browserState.value.selectedTab
    }

    /**
     * Checks if the given tab ID is currently selected.
     */
    fun isSelected(tabId: String): Boolean {
        return browserState.value.selectedTabId == tabId
    }
}
