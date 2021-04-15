package com.wpe.examples.minibrowser

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