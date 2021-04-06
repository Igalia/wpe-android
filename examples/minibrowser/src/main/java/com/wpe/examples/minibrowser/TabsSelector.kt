package com.wpe.examples.minibrowser

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.android.synthetic.main.tabs_selector.*

class TabsSelector(tabs: ArrayList<Tab>) : BottomSheetDialogFragment() {

    private val tabs: ArrayList<Tab> = tabs

    private class TabsListAdapter(context: Context, tabs: ArrayList<Tab>) : BaseAdapter() {
        private val context: Context = context
        private val tabs: ArrayList<Tab> = tabs

        override fun getCount(): Int {
            return tabs.count()
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getItem(position: Int): Any {
            return "TEST STRING"
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val layoutInflater = LayoutInflater.from(context)
            val row = layoutInflater.inflate(R.layout.tabs_selector_row, parent, false)
            return row
        }
    }

    companion object {
        const val TAG = "TabsSelector"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.tabs_selector, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        tabsList.adapter = TabsListAdapter(requireContext(), tabs)
    }
}