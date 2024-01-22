package com.wpe.webdriver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.wpe.wpe.Browser
import com.wpe.wpe.ProcessType
import com.wpe.wpe.services.WPEServiceConnection
import com.wpe.wpe.services.WPEServiceConnectionListener
import com.wpe.wpeview.WPEView

class WebDriverActivity : AppCompatActivity() {

    private val LOGTAG = "WebDriver"

    private val serviceConnectionDelegate: WPEServiceConnectionListener = object : WPEServiceConnectionListener {
        override fun onCleanExit(connection: WPEServiceConnection) {
            Log.d(LOGTAG, "onCleanExit for process: " + connection.pid)
        }

        override fun onServiceDisconnected(connection: WPEServiceConnection) {
            Log.d(LOGTAG, "onServiceDisconnected for process: " + connection.pid)
            // TODO: Unbind the service to prevent restarting on crash,
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Browser.getInstance().isAutomationMode = true

        val webView = WPEView(applicationContext)
        setContentView(webView);

        webView.loadUrl("about:blank")

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
}
