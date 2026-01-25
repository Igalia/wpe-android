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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.wpewebkit.wpeview.WPEChromeClient
import org.wpewebkit.wpeview.WPEView
import org.wpewebkit.wpeview.WPEViewClient
import java.util.UUID

/**
 * Represents a browser tab with observable state for Compose UI.
 * Each tab has its own WPEWebView instance and manages its own navigation history.
 */
class Tab(
    val id: String,
    val webview: WPEView
) {
    /** Current page title */
    var title by mutableStateOf("")
        private set

    /** Current page URL */
    var url by mutableStateOf("")
        private set

    /** Page loading progress (0.0 to 1.0) */
    var progress by mutableFloatStateOf(0f)
        private set

    /** Whether the page is currently loading */
    var isLoading by mutableStateOf(false)
        private set

    /** Whether the tab can navigate back in history */
    var canGoBack by mutableStateOf(false)
        private set

    /** Whether the tab can navigate forward in history */
    var canGoForward by mutableStateOf(false)
        private set

    init {
        setupCallbacks()
    }

    private fun setupCallbacks() {
        webview.wpeChromeClient = object : WPEChromeClient {
            override fun onProgressChanged(view: WPEView, newProgress: Int) {
                progress = newProgress / 100f
                isLoading = newProgress in 1..99
                updateNavigationState()
            }

            override fun onUriChanged(view: WPEView, uri: String) {
                url = uri
                updateNavigationState()
            }

            override fun onReceivedTitle(view: WPEView, newTitle: String) {
                title = newTitle
            }
        }

        webview.wpeViewClient = object : WPEViewClient() {
            override fun onPageStarted(view: WPEView, pageUrl: String) {
                url = pageUrl
                isLoading = true
                updateNavigationState()
            }

            override fun onPageFinished(view: WPEView, pageUrl: String) {
                isLoading = false
                updateNavigationState()
            }
        }
    }

    private fun updateNavigationState() {
        canGoBack = webview.canGoBack()
        canGoForward = webview.canGoForward()
    }

    fun loadUrl(newUrl: String) {
        webview.loadUrl(newUrl)
    }

    fun goBack() {
        if (webview.canGoBack()) {
            webview.goBack()
        }
    }

    fun goForward() {
        if (webview.canGoForward()) {
            webview.goForward()
        }
    }

    fun reload() {
        webview.reload()
    }

    fun stopLoading() {
        webview.stopLoading()
    }

    internal fun setInitialUrl(initialUrl: String) {
        url = initialUrl
    }

    companion object {
        fun newTab(
            context: Context,
            url: String
        ): Tab {
            val webview = WPEView(context).apply {
                loadUrl(url)
            }
            return Tab(
                id = UUID.randomUUID().toString(),
                webview = webview
            ).apply {
                setInitialUrl(url)
            }
        }
    }
}
