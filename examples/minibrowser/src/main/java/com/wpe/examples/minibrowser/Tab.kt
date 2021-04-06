package com.wpe.examples.minibrowser

import com.wpe.wpeview.WPEView
import com.wpe.wpeview.WebChromeClient

class Tab(browser: MainActivity, view: WPEView, url: String) {
    private val browser: MainActivity = browser
    internal val view: WPEView = view
    private val url: String = url

    init {
        view.loadUrl(url)
        browser.setUrl(url)
    }
}