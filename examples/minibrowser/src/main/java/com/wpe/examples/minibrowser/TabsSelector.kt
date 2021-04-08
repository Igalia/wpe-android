package com.wpe.examples.minibrowser

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.android.synthetic.main.tabs_selector.*

class TabsSelector(tabs: ArrayList<TabSelectorItem>) : BottomSheetDialogFragment() {

    private val tabs: ArrayList<TabSelectorItem> = tabs

    private class TabsListAdapter(context: Context, tabs: ArrayList<TabSelectorItem>) : BaseAdapter() {
        private val context: Context = context
        private val tabs: ArrayList<TabSelectorItem> = tabs

        override fun getCount(): Int {
            return tabs.count()
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getItem(position: Int): Any {
            return tabs[position];
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val layoutInflater = LayoutInflater.from(context)
            val row = layoutInflater.inflate(R.layout.tabs_selector_row, parent, false)

            val title = row.findViewById<TextView>(R.id.tabTitle)
            title.text = tabs[position].tab.view?.title
            val subtitle = row.findViewById<TextView>(R.id.tabSubtitle)
            subtitle.text = tabs[position].tab.view?.url

            val closeButton = row.findViewById<ImageView>(R.id.closeButton)
            closeButton.setOnClickListener {
                tabs[position].close()
                notifyDataSetChanged()
            }

            return row
        }
    }

    companion object {
        const val TAG = "TabsSelector"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.tabs_selector, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val addButton = view.findViewById<FloatingActionButton>(R.id.newTabButton)
        addButton.setOnClickListener {
            (activity as MainActivity).newTab("about:blank")
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        tabsList.adapter = TabsListAdapter(requireContext(), tabs)
    }
}