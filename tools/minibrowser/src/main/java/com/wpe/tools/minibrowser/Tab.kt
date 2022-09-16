/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.wpe.wpeview.WPEView

class Tab : Fragment(R.layout.tab_fragment) {
    internal var view: WPEView? = null
    private var url: String? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        url = arguments?.getString("url")
    }

    override fun onViewCreated(aView: View, savedInstanceState: Bundle?) {
        super.onViewCreated(aView, savedInstanceState)
        view = aView.findViewById(R.id.wpe_view)
        (activity as MainActivity).registerTab(this)
        view?.loadUrl(url!!)
        (activity as MainActivity).setUrl(url!!)
    }

    internal fun close(item: TabSelectorItem) {
        (activity as MainActivity).closeTab(item)
    }
}
