package com.wpe.examples.minibrowser

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import com.wpe.wpe.WPEView

const val INITIAL_URL = "https://igalia.com"
const val SEARCH_URI_BASE = "https://duckduckgo.com/?q="

class MainActivity : AppCompatActivity() {
    private lateinit var urlEditText: EditText

    // TODO Tabs support
    private var browser: WPEView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupToolbar()

        setupUrlEditText()

        browser = findViewById(R.id.wpe_view)
        browser?.loadUrl(INITIAL_URL)
        urlEditText.setText(INITIAL_URL)
    }

    private fun setupToolbar() {
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.displayOptions = ActionBar.DISPLAY_SHOW_CUSTOM
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

    fun onCommit(text: String) {
        if ((text.contains(".") || text.contains(":")) && !text.contains(" ")) {
            browser?.loadUrl(text)
        } else {
            browser?.loadUrl(SEARCH_URI_BASE + text)
        }
        browser?.requestFocus()
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