package com.wpe.tools.minibrowser

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.android.synthetic.main.tabs_selector.*

class TabsSelector(tabs: ArrayList<TabSelectorItem>, active: Int) : BottomSheetDialogFragment() {

    private val tabs: ArrayList<TabSelectorItem> = tabs
    internal var selected: Int = active

    private class TabsListAdapter(
        parent: BottomSheetDialogFragment,
        context: Context,
        tabs: ArrayList<TabSelectorItem>
    ) : BaseAdapter() {
        private val bottomSheet: BottomSheetDialogFragment = parent
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
                if (tabs.size == 0) {
                    bottomSheet.dismiss()
                }
            }

            if (position == (bottomSheet as TabsSelector).selected) {
                row.setBackgroundColor(MaterialColors.getColor(context, R.attr.colorSecondary, Color.GRAY))
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
            (tabsList.adapter as BaseAdapter).notifyDataSetChanged()
            selected = tabs.size
            dismiss()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        tabsList.adapter = TabsListAdapter(this, requireContext(), tabs)
        tabsList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            selected = position
            val adapter = (tabsList.adapter as BaseAdapter);
            adapter.notifyDataSetChanged()
            val item = (adapter.getItem(position) as TabSelectorItem)
            (activity as MainActivity).setActiveTab(item);
            dismiss()
        };
    }
}
