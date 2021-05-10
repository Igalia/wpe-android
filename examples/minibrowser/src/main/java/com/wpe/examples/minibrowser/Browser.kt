package com.wpe.examples.minibrowser

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.add
import androidx.fragment.app.commit
import com.wpe.wpeview.WPEView
import com.wpe.wpeview.WPEViewClient
import com.wpe.wpeview.WebChromeClient

const val INITIAL_URL = "https://igalia.com"
const val SEARCH_URI_BASE = "https://duckduckgo.com/?q="

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    private lateinit var urlEditText: EditText
    private lateinit var progressView: ProgressBar

    private var activeTab: Tab? = null
    private var activeTabItem: TabSelectorItem? = null
    private var tabs: ArrayList<TabSelectorItem> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupToolbar()
        setupUrlEditText()

        newTab(INITIAL_URL)
    }

    internal fun newTab(url: String?) {
        val charPool : List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val tabId = (1..12)
            .map { _ -> kotlin.random.Random.nextInt(0, charPool.size) }
            .map(charPool::get)
            .joinToString("");
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
        val index = tabs.indexOf(tab);
        if (activeTabItem == tab) {
            val next = (index + 1) % tabs.size;
            activeTabItem = tabs[next];
            setActiveTab(activeTabItem!!)
        }
        tabs.removeAt(index);
        if (tabs.size == 0) {
            setUrl(null)
        }

        val tab = supportFragmentManager.findFragmentByTag(tab.tab.tag) ?: return
        supportFragmentManager.commit {
            remove(tab)
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
        activeTab?.view?.webChromeClient = object : WebChromeClient {
            override fun onProgressChanged(view: WPEView?, progress: Int) {
                super.onProgressChanged(view, progress)
                progressView.progress = progress
                if (progress in 1..99) {
                    progressView.visibility = View.VISIBLE
                } else {
                    progressView.visibility = View.GONE
                }
            }
        };
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

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        activeTab?.view?.canGoBack()?.let { menu?.findItem(R.id.action_back)?.setEnabled(it) }
        activeTab?.view?.canGoForward()?.let { menu?.findItem(R.id.action_forward)?.setEnabled(it) }
        return super.onPrepareOptionsMenu(menu)
    }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.actions, menu)
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

    override fun onBackPressed() {
        if (activeTab?.view?.canGoBack()!!) {
            activeTab?.view?.goBack()
        } else {
            super.onBackPressed()
        }
    }

    fun onCommit(text: String) {
        if (text == null) {
            return;
        }
        var url: String = if ((text.contains(".") || text.contains(":")) && !text.contains(" ")) {
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