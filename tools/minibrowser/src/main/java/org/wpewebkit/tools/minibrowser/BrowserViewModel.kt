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

    fun addTab(tab: Tab, select: Boolean = true) {
        _browserState.update {
            it.copy(
                tabs = it.tabs + tab,
                selectedTabId = if (select) tab.id else it.selectedTabId
            )
        }
    }

    fun createTab(context: Context, url: String = INITIAL_URL): Tab {
        val tab = Tab.newTab(context, url)
        addTab(tab)
        return tab
    }

    fun selectTab(tabId: String) {
        _browserState.update {
            it.copy(selectedTabId = tabId)
        }
    }

    fun removeTab(tabId: String) {
        _browserState.update { state ->
            val newTabs = state.tabs.filter { it.id != tabId }
            val newSelectedId = when {
                state.selectedTabId != tabId -> state.selectedTabId
                newTabs.isEmpty() -> null
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

    fun closeAllTabs() {
        _browserState.update {
            it.copy(tabs = emptyList(), selectedTabId = null)
        }
    }

    fun findTab(id: String): Tab? {
        return browserState.value.tabs.find { tab -> tab.id == id }
    }

    fun getTab(id: String): Tab {
        return browserState.value.tabs.first { tab -> tab.id == id }
    }

    fun selectedTab(): Tab? {
        return browserState.value.selectedTab
    }

    fun isSelected(tabId: String): Boolean {
        return browserState.value.selectedTabId == tabId
    }

    fun moveTab(fromIndex: Int, toIndex: Int) {
        _browserState.update { state ->
            val tabs = state.tabs.toMutableList()
            if (fromIndex in tabs.indices && toIndex in tabs.indices) {
                val item = tabs.removeAt(fromIndex)
                tabs.add(toIndex, item)
            }
            state.copy(tabs = tabs)
        }
    }
}
