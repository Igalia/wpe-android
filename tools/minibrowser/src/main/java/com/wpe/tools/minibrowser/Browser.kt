/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Imanol Fernandez <ifernandez@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

package com.wpe.tools.minibrowser

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.add
import androidx.fragment.app.commit
import com.google.android.material.color.MaterialColors
import com.wpe.wpeview.WPEChromeClient
import com.wpe.wpeview.WPEView
import com.wpe.wpeview.WPEViewClient

const val INITIAL_URL = "https://igalia.com"
const val SEARCH_URI_BASE = "https://duckduckgo.com/?q="

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private lateinit var urlEditText: EditText
    private lateinit var progressView: ProgressBar

    private var activeTab: Tab? = null
    private var activeTabItem: TabSelectorItem? = null
    private var tabs: ArrayList<TabSelectorItem> = ArrayList()

    private var fullscreenView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupToolbar()
        setupUrlEditText()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeTab?.view?.canGoBack() == true) {
                    activeTab?.view?.goBack()
                } else {
                    finish()
                }
            }
        })

        newTab(INITIAL_URL)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("Browser", "onConfigurationChanged")
    }

    internal fun newTab(url: String?) {
        val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val tabId = (1..12)
            .map { kotlin.random.Random.nextInt(0, charPool.size) }
            .map(charPool::get)
            .joinToString("")
        val bundle = bundleOf("url" to url)
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            add<Tab>(R.id.fragment_container_view, tag = tabId, args = bundle)
        }
    }

    internal fun registerTab(tab: Tab) {
        activeTab = tab
        activeTabItem = TabSelectorItem(activeTab!!)
        tabs.add(activeTabItem!!)

        setChromeClient()
        setWPEViewClient()
    }

    internal fun closeTab(tab: TabSelectorItem) {
        val index = tabs.indexOf(tab)
        if (activeTabItem == tab) {
            val next = (index + 1) % tabs.size
            activeTabItem = tabs[next]
            setActiveTab(activeTabItem!!)
        }
        tabs.removeAt(index)
        if (tabs.size == 0) {
            setUrl(null)
        }

        val tab2 = supportFragmentManager.findFragmentByTag(tab.tab.tag) ?: return
        supportFragmentManager.commit {
            remove(tab2)
        }
    }

    fun setActiveTab(item: TabSelectorItem) {
        activeTab = item.tab
        activeTabItem = item
        setUrl(activeTab?.view?.url)
        supportFragmentManager.commit {
            for (fragment in supportFragmentManager.fragments) {
                if (fragment == activeTab) {
                    show(fragment)
                } else {
                    hide(fragment)
                }
            }
        }
    }

    private fun showTabsSelector() {
        TabsSelector(tabs, tabs.indexOf(activeTabItem)).apply {
            show(supportFragmentManager, TabsSelector.TAG)
        }
    }

    fun setUrl(url: String?) {
        if (url != null)
            urlEditText.setText(url)
        else
            urlEditText.text.clear()
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.displayOptions = ActionBar.DISPLAY_SHOW_CUSTOM

        progressView = findViewById(R.id.page_progress)
    }

    private fun setupUrlEditText() {
        urlEditText = findViewById(R.id.location_view)
        urlEditText.setOnEditorActionListener(object : View.OnFocusChangeListener,
            TextView.OnEditorActionListener {
            override fun onFocusChange(view: View?, hasFocus: Boolean) = Unit
            override fun onEditorAction(
                textView: TextView,
                actionId: Int,
                event: KeyEvent?
            ): Boolean {
                hideKeyboard()
                onCommit(textView.text.toString())
                return true
            }
        })
    }

    private fun setChromeClient() {
        activeTab?.view?.wpeChromeClient = object : WPEChromeClient {
            override fun onProgressChanged(view: WPEView?, progress: Int) {
                super.onProgressChanged(view, progress)
                progressView.progress = progress
                if (progress in 1..99) {
                    progressView.visibility = View.VISIBLE
                } else {
                    progressView.visibility = View.GONE
                }
            }

            override fun onShowCustomView(view: View?, callback: WPEChromeClient.CustomViewCallback?) {
                fullscreenView?.let {
                    (it.parent as ViewGroup).removeView(it)
                }
                fullscreenView = view
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                } else {
                    @Suppress("DEPRECATION")
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN
                    )
                }
                window.addContentView(
                    fullscreenView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
                    )
                )
            }

            override fun onHideCustomView() {
                fullscreenView?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    } else {
                        @Suppress("DEPRECATION")
                        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    }
                    (it.parent as ViewGroup).removeView(it)
                }
                fullscreenView = null
            }
        }
    }

    private fun setWPEViewClient() {
        activeTab?.view?.wpeViewClient = object : WPEViewClient {
            override fun onPageStarted(view: WPEView?, url: String?) {
                super.onPageStarted(view, url)
                if (url != null) {
                    setUrl(url)
                }
            }

            override fun onPageFinished(view: WPEView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null) {
                    setUrl(url)
                }
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        activeTab?.view?.canGoBack()?.let { menu.findItem(R.id.action_back)?.setEnabled(it) }
        activeTab?.view?.canGoForward()?.let { menu.findItem(R.id.action_forward)?.setEnabled(it) }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.actions, menu)

        // FIXME: There is a bug with androidx.appcompat 1.2.0 that doesn't take into account
        // the android:iconTint attribute in actions.xml menu description. So, we need to force
        // the tint color here by code to apply it visually. Normally this bug should be fixed
        // by upgrading the androidx.appcompat dependency, then this block of code should be removed.
        val item = menu.findItem(R.id.action_tab)
        val icon = item?.icon
        icon?.setTint(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, Color.BLACK))
        item?.icon = icon

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_back -> {
            activeTab?.view?.goBack()
            true
        }

        R.id.action_forward -> {
            activeTab?.view?.goForward()
            true
        }

        R.id.action_stop -> {
            activeTab?.view?.stopLoading()
            true
        }

        R.id.action_reload -> {
            activeTab?.view?.reload()
            true
        }

        R.id.action_tab -> {
            showTabsSelector()
            true
        }

        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    fun onCommit(text: String) {
        val url: String = if ((text.contains(".") || text.contains(":")) && !text.contains(" ")) {
            text
        } else {
            SEARCH_URI_BASE + text
        }
        if (activeTab != null) {
            activeTab?.view?.loadUrl(url)
            activeTab?.view?.requestFocus()
        } else {
            newTab(url)
        }
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val manager: InputMethodManager = getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager
            manager.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}
