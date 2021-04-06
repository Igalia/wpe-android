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
import com.wpe.wpeview.WPEView
import com.wpe.wpeview.WebChromeClient

const val INITIAL_URL = "https://igalia.com"
const val SEARCH_URI_BASE = "https://duckduckgo.com/?q="

class MainActivity : AppCompatActivity() {
    private lateinit var urlEditText: EditText
    private lateinit var progressView: ProgressBar

    private var activeTab: Tab? = null
    private var tabs: ArrayList<Tab> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupToolbar()
        setupUrlEditText()

        openTab(INITIAL_URL)

        setChromeClient()
    }

    private fun openTab(url: String) {
        activeTab = Tab(this, findViewById(R.id.wpe_view), url)
        tabs.add(activeTab!!)
    }

    fun setUrl(url: String) {
        urlEditText.setText(url)
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
        activeTab?.view?.webChromeClient = object : WebChromeClient() {
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
        if ((text.contains(".") || text.contains(":")) && !text.contains(" ")) {
            activeTab?.view?.loadUrl(text)
        } else {
            activeTab?.view?.loadUrl(SEARCH_URI_BASE + text)
        }
        activeTab?.view?.requestFocus()
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

    private fun showTabsSelector() {
        TabsSelector(tabs).apply {
            show(supportFragmentManager, TabsSelector.TAG)
        }
    }
}