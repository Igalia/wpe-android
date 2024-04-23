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


package com.wpe.webdriver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.wpe.wpe.ProcessType
import com.wpe.wpe.services.WPEServiceConnection
import com.wpe.wpe.services.WPEServiceConnectionListener
import com.wpe.wpeview.WPEChromeClient
import com.wpe.wpeview.WPEContext
import com.wpe.wpeview.WPEView
import java.net.ServerSocket
import kotlin.system.exitProcess


class WebDriverActivity : AppCompatActivity() {

    private val LOGTAG = "WebDriver"

    private lateinit var wpeContext: WPEContext
    private var wpeViewMap= mutableMapOf<Int, WPEView>()
    private var freePort = getFreePort()
    private var isHeadless = false

    private val serviceConnectionDelegate: WPEServiceConnectionListener = object : WPEServiceConnectionListener {
        override fun onCleanExit(connection: WPEServiceConnection) {
            Log.d(LOGTAG, "onCleanExit for automation sesession in port: " + connection.pid)
            exitProcess(0)
        }

        override fun onServiceDisconnected(connection: WPEServiceConnection) {
            Log.d(LOGTAG, "onServiceDisconnected for process: " + connection.pid)
            // TODO: Unbind the service to prevent restarting on crash,
        }
    }

    private val wpeChromeClient: WPEChromeClient = object : WPEChromeClient {
        override fun onCloseWindow(wpeView: WPEView) {
            wpeView.parent?.let {p ->
                (p as ViewGroup).removeView(wpeView)
            }

            wpeViewMap.remove(wpeView.hashCode())
            wpeView.destroy()
        }
    }

    private val wpeContextClient: WPEContext.Client  = object : WPEContext.Client {

        override fun createWPEViewForAutomation(): WPEView? {
            val wpeView = createWPEView()
            wpeViewMap[wpeView.hashCode()] = wpeView
            return wpeView
        }

        private fun createWPEView() : WPEView {
            val view = WPEView(wpeContext, isHeadless)
            view.wpeChromeClient = wpeChromeClient

            if (!isHeadless) {
                setContentView(view)
                view.loadUrl("about:blank")
            }

            return view
        }
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
            val processType = ProcessType.WebDriverProcess
            val serviceClass =
                Class.forName("com.wpe.wpe.services." + processType.name + "Service")
            val parcelFd = ParcelFileDescriptor.fromFd(0)

            val intent = Intent(applicationContext, serviceClass)
            val serviceConnection = WPEServiceConnection(
                freePort.toLong(), processType, parcelFd,
                serviceConnectionDelegate)
            applicationContext.bindService(
                intent, serviceConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (e: Exception) {
            Log.e(LOGTAG, "Cannot launch webdriver process", e)
        }

        wpeContext = WPEContext(applicationContext, freePort, true)
        wpeContext.setClient(wpeContextClient)
    }

    override fun onDestroy() {
        for ((key, view) in wpeViewMap) {
            view.destroy()
            wpeViewMap.remove(key)
        }

        super.onDestroy()
    }
}
