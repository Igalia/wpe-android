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

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BrowserState(
    val tabs: List<Tab> = emptyList(),
    val selectedTabId: String? = null,
)

class BrowserViewModel : ViewModel() {
    private val _browserState = MutableStateFlow(BrowserState())
    val browserState = _browserState.asStateFlow()

    fun addTab(tab: Tab) {
        _browserState.update {
            it.copy(
                tabs = it.tabs + tab,
                selectedTabId = tab.id // Todo maybe make selection optional
            )
        }
    }

    fun findTab(id: String) : Tab {
        return browserState.value.tabs.first{ tab -> tab.id == id }
    }
}
