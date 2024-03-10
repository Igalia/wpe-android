package com.wpe.webdriver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.wpe.wpe.Browser
import com.wpe.wpe.ProcessType
import com.wpe.wpe.services.WPEServiceConnection
import com.wpe.wpe.services.WPEServiceConnectionListener
import com.wpe.wpeview.WPEChromeClient
import com.wpe.wpeview.WPEContext
import com.wpe.wpeview.WPEView

class WebDriverActivity : AppCompatActivity() {

    private val LOGTAG = "WebDriver"

    private lateinit var wpeContext: WPEContext
    private var wpeView: WPEView? = null

    private val serviceConnectionDelegate: WPEServiceConnectionListener = object : WPEServiceConnectionListener {
        override fun onCleanExit(connection: WPEServiceConnection) {
            Log.d(LOGTAG, "onCleanExit for process: " + connection.pid)
        }

        override fun onServiceDisconnected(connection: WPEServiceConnection) {
            Log.d(LOGTAG, "onServiceDisconnected for process: " + connection.pid)
            // TODO: Unbind the service to prevent restarting on crash,
        }
    }

    private val wpeChromeClient: WPEChromeClient = object : WPEChromeClient {
        override fun onCloseWindow(window: WPEView) {
            wpeView?.let {
                it.parent?.let {p ->
                    (p as ViewGroup).removeView(it)
                }
                it.destroy()
            }
            wpeView = null
        }
    }

    private val wpeContextClient: WPEContext.Client  = object : WPEContext.Client {
        override fun createWPEViewForAutomation(): WPEView? {
            return wpeView ?: run {
                wpeView = createWPEView()
                wpeView
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wpeContext = WPEContext(applicationContext, true)
        wpeContext.setClient(wpeContextClient)
        wpeView = createWPEView()

        try {
            val processType = ProcessType.WebDriverProcess
            val serviceClass =
                Class.forName("com.wpe.wpe.services." + processType.name + "Service")
            val parcelFd = ParcelFileDescriptor.fromFd(0)
            Log.v(LOGTAG, "Launching service: " + processType.name)
            val intent = Intent(applicationContext, serviceClass)
            val serviceConnection = WPEServiceConnection(0, processType, parcelFd, serviceConnectionDelegate)
            applicationContext.bindService(
                intent, serviceConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (e: Exception) {
            Log.e(LOGTAG, "Cannot launch webdriver process", e)
        }
    }

    private fun createWPEView() : WPEView? {
        val view = WPEView(wpeContext)
        view.wpeChromeClient = wpeChromeClient
        setContentView(view)

        view.loadUrl("about:blank")

        return view
    }
}
