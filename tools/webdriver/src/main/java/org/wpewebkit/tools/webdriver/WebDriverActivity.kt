/**
 * Copyright (C) 2024 Igalia S.L. <info@igalia.com>
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


package org.wpewebkit.tools.webdriver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import org.wpewebkit.wpe.WKProcessType
import org.wpewebkit.wpe.services.WPEServiceConnection
import org.wpewebkit.wpe.services.WPEServiceConnectionListener
import org.wpewebkit.wpeview.WebChromeClient
import org.wpewebkit.wpeview.WebContext
import org.wpewebkit.wpeview.WebView
import java.net.ServerSocket
import kotlin.system.exitProcess


class WebDriverActivity : AppCompatActivity() {

    private val LOGTAG = "WebDriver"

    private lateinit var webContext: WebContext
    private val webViewMap = mutableMapOf<Int, WebView>()
    private var freePort = getFreePort()
    private var isHeadless = false

    private val serviceConnectionDelegate: WPEServiceConnectionListener = object :
        WPEServiceConnectionListener {
        override fun onCleanExit(connection: WPEServiceConnection) {
            Log.d(LOGTAG, "onCleanExit for automation sesession in port: " + connection.pid)
            exitProcess(0)
        }

        override fun onServiceDisconnected(connection: WPEServiceConnection) {
            Log.d(LOGTAG, "onServiceDisconnected for process: " + connection.pid)
            // TODO: Unbind the service to prevent restarting on crash,
        }
    }

    private val webChromeClient: WebChromeClient = object : WebChromeClient() {
        override fun onCloseWindow(window: WebView) {
            (window.parent as? ViewGroup)?.removeView(window)
            webViewMap.remove(window.hashCode())
            window.destroy()
        }
    }

    private val webContextClient: WebContext.Client = WebContext.Client {
        val view = WebView(webContext, isHeadless).apply {
            setWebChromeClient(webChromeClient)
            if (!isHeadless) {
                setContentView(this)
                loadUrl("about:blank")
            }
        }
        webViewMap[view.hashCode()] = view
        view
    }

    private fun getFreePort(): Int {
        var freePort = 0
        try {
            ServerSocket(0).use { s -> freePort = s.localPort }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return freePort
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.hasExtra("headless")) {
            isHeadless = true
        }

        try {
            val processType = WKProcessType.WebDriverProcess
            val serviceClass =
                Class.forName("org.wpewebkit.wpe.services." + processType.name + "Service")
            val parcelFd = ParcelFileDescriptor.fromFd(0)

            val intent = Intent(applicationContext, serviceClass)
            val serviceConnection = WPEServiceConnection(
                freePort.toLong(), processType, parcelFd,
                serviceConnectionDelegate
            )
            applicationContext.bindService(
                intent, serviceConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (e: Exception) {
            Log.e(LOGTAG, "Cannot launch webdriver process", e)
        }

        WebContext.enableRemoteInspector(freePort, false)

        webContext = WebContext(applicationContext, true)
        webContext.setClient(webContextClient)
    }

    override fun onDestroy() {
        if (::webContext.isInitialized) {
            webContext.setClient(null)
        }

        val iterator = webViewMap.values.iterator()
        while (iterator.hasNext()) {
            iterator.next().destroy()
            iterator.remove()
        }

        if (::webContext.isInitialized) {
            webContext.destroy()
        }

        super.onDestroy()
    }
}
