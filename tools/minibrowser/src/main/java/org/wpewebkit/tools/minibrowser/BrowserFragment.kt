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
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import org.wpewebkit.tools.minibrowser.R
import org.wpewebkit.tools.minibrowser.databinding.FragmentBrowserBinding
import org.wpewebkit.tools.minibrowser.requestApplyStandardInsets
import org.wpewebkit.wpeview.WPEChromeClient
import org.wpewebkit.wpeview.WPEView
import org.wpewebkit.wpeview.WPEViewClient


const val INITIAL_URL = "https://igalia.com"
const val SEARCH_URI_BASE = "https://duckduckgo.com/?q="

class BrowserFragment : Fragment(R.layout.fragment_browser) {

    private val TAG = "BrowserFragment"

    private lateinit var binding: FragmentBrowserBinding

    private val browserViewModel by activityViewModels<BrowserViewModel>()

    private var fullscreenView: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.i(TAG, "onCreateView")
        binding = FragmentBrowserBinding.inflate(inflater, container, false)
        binding.toolbar.inflateMenu(R.menu.main_menu)

        binding.toolbar.setOnMenuItemClickListener {
            when(it.itemId) {
                R.id.action_settings -> {
                    findNavController().navigate(R.id.action_browserFragment_to_settingsFragment)
                }
                R.id.action_back -> {
                    selectedTab().webview.goBack()
                }
                R.id.action_forward -> {
                    selectedTab().webview.goForward()
                }
                R.id.action_reload -> {
                    selectedTab().webview.reload()
                }
                else -> {}
            }
            true
        }

        binding.toolbarEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                onCommit(binding.toolbarEditText.text.toString())
                binding.toolbarEditText.clearFocus()
                selectedTab().webview.requestFocus()
                true
            } else {
                false
            }
        }

        return binding.root
    }

    override fun onDestroyView() {
        Log.i(TAG, "onDestroyView")
        super.onDestroyView()
        binding.tabContainerView.removeAllViews()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Log.i(TAG, "onViewCreated")
        super.onViewCreated(view, savedInstanceState)
        view.requestApplyStandardInsets()

        val currentState = browserViewModel.browserState.value
        currentState.selectedTabId?.let { selectedTabId ->
            val tab = browserViewModel.findTab(selectedTabId)
            binding.tabContainerView.addView(tab.webview)
            tab.webview.url.let {
                binding.toolbarEditText.setText(tab.webview.url)
            }
        } ?:run {
            val tab = Tab.newTab(requireContext(), INITIAL_URL)
            browserViewModel.addTab(tab)
            binding.tabContainerView.addView(tab.webview)
            binding.toolbarEditText.setText(tab.webview.url)
        }

        val selectedTab = selectedTab()
        if (selectedTab.webview.wpeChromeClient == null) {
            selectedTab.webview.wpeChromeClient = object : WPEChromeClient {
                override fun onProgressChanged(view: WPEView, progress: Int) {
                    super.onProgressChanged(view, progress)
                    binding.pageProgress.progress = progress
                    if (progress in 1..99) {
                        binding.pageProgress.visibility = View.VISIBLE
                    } else {
                        binding.pageProgress.visibility = View.GONE
                    }
                }

                override fun onShowCustomView(view: View, callback: WPEChromeClient.CustomViewCallback) {
                    fullscreenView?.let {
                        (it.parent as ViewGroup).removeView(it)
                    }
                    fullscreenView = view
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        requireActivity().window.insetsController?.hide(
                            WindowInsets.Type.statusBars() or WindowInsets
                            .Type.navigationBars())
                    } else {
                        @Suppress("DEPRECATION")
                        requireActivity().window.setFlags(
                            WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            WindowManager.LayoutParams.FLAG_FULLSCREEN
                        )
                    }
                    requireActivity().window.addContentView(
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
                            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        } else {
                            @Suppress("DEPRECATION")
                            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        }
                        (it.parent as ViewGroup).removeView(it)
                    }
                    fullscreenView = null
                }

                override fun onUriChanged(view: WPEView, uri: String) {
                    super.onUriChanged(view, uri)
                    binding.toolbarEditText.setText(uri)
                }
            }
        }

        selectedTab.webview.wpeViewClient = object : WPEViewClient() {
            override fun onPageStarted(view: WPEView, url: String) {
                super.onPageStarted(view, url)
                binding.toolbarEditText.setText(url)
            }
        }
    }

    override fun onStart() {
        Log.i(TAG, "onStart")
        super.onStart()
    }

    override fun onStop() {
        Log.i(TAG, "onStop")
        super.onStop()
    }

    override fun onAttach(context: Context) {
        Log.i(TAG, "onAttach")
        super.onAttach(context)
    }

    override fun onDetach() {
        Log.i(TAG, "onDetach")
        super.onDetach()
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        super.onPause()
    }

    override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun onCommit(text: String) {
        val url: String = if ((text.contains(".") || text.contains(":")) && !text.contains(" ")) {
            Utils.normalizeAddress(text)
        } else {
            SEARCH_URI_BASE + text
        }

        selectedTab().webview.loadUrl(url)
    }

    private fun hideKeyboard() {
        val manager = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(requireView().windowToken, 0)
    }

    private fun selectedTab() : Tab {
        // For now assume we always have at least one tab
        val selectedTabId = browserViewModel.browserState.value.selectedTabId
        return browserViewModel.findTab(selectedTabId!!)
    }
}
