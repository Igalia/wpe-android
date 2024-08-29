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

package org.wpewebkit.tools.minibrowser.settings.clearbrowsingdata

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import org.wpewebkit.tools.minibrowser.BrowserViewModel
import org.wpewebkit.tools.minibrowser.R
import org.wpewebkit.tools.minibrowser.databinding.FragmentClearBrowsingDataBinding
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch

class ClearBrowsingDataFragment : Fragment(R.layout.fragment_clear_browsing_data) {

    private lateinit var binding: FragmentClearBrowsingDataBinding

    private val browserViewModel by activityViewModels<BrowserViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = FragmentClearBrowsingDataBinding.bind(view)

        val toolbar = view.findViewById<Toolbar>(R.id.settingsToolbar)
        toolbar.apply {
            title = getString(R.string.preferences_clear_browsing_data)
            navigationIcon = ResourcesCompat.getDrawable(requireActivity().resources,
                R.drawable.ic_round_arrow_back_24, null)
        }
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.cookiesItem.onCheckListener = {
                when (it) {
                    true -> binding.btnClearData.isEnabled = true
                    else -> binding.btnClearData.isEnabled = false
                }
        }

        binding.btnClearData.setOnClickListener {
            showDeleteDialog()
        }
    }

    private fun showDeleteDialog() {
        context?.let {
            AlertDialog.Builder(it).apply {
                setMessage(
                    it.getString(R.string.clear_browsing_data_prompt_message),
                )

                setNegativeButton(R.string.clear_browsing_data_prompt_cancel) { it: DialogInterface, _ ->
                    it.cancel()
                }

                setPositiveButton(R.string.Clear_browsing_data_prompt_ok) { it: DialogInterface, _ ->
                    it.dismiss()
                    clearSelected()
                }
                create()
            }.show()
        }
    }

    private fun clearSelected() {
        if (binding.cookiesItem.isChecked) {
            val selectedTabId = browserViewModel.browserState.value.selectedTabId
            val wpeView = browserViewModel.findTab(selectedTabId!!).webview
            wpeView.cookieManager.removeAllCookies {
                Snackbar.make(
                    requireView(),
                    R.string.preferences_clear_browsing_data_snackbar,
                    Snackbar.LENGTH_SHORT
                ).show()

                navigateBack()
            }
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        viewLifecycleOwner.lifecycleScope.launch(Main) {
            findNavController().apply {
                popBackStack(R.id.browserFragment, false)
                navigate(ClearBrowsingDataFragmentDirections.actionGlobalSettingsFragment())
            }
        }
    }
}
