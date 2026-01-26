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

private const val CAPTURE_STATE_ACTIVE = 1

class Tab(
    val id: String,
    val webview: WPEView
) {
    var title by mutableStateOf("")
        private set

    var url by mutableStateOf("")
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var canGoBack by mutableStateOf(false)
        private set

    var canGoForward by mutableStateOf(false)
        private set

    // visibility state for background tab optimization
    var isVisible by mutableStateOf(true)
        private set

    // media state indicators
    var isPlayingAudio by mutableStateOf(false)
        private set

    var isCameraActive by mutableStateOf(false)
        private set

    var isMicrophoneActive by mutableStateOf(false)
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

            override fun onAudioStateChanged(view: WPEView, playing: Boolean) {
                isPlayingAudio = playing
            }

            override fun onCameraCaptureStateChanged(view: WPEView, state: Int) {
                isCameraActive = state == CAPTURE_STATE_ACTIVE
            }

            override fun onMicrophoneCaptureStateChanged(view: WPEView, state: Int) {
                isMicrophoneActive = state == CAPTURE_STATE_ACTIVE
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

    fun setVisibility(visible: Boolean) {
        if (isVisible == visible) return
        isVisible = visible
        if (visible) {
            // tab becoming visible - unmute and refresh layout
            webview.setMuted(false)
            webview.requestLayout()
        } else {
            // tab going to background - mute to save resources
            webview.setMuted(true)
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
