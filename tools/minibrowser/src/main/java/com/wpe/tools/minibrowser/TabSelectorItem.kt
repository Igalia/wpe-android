package com.wpe.tools.minibrowser

class TabSelectorItem(tab: Tab) {
    internal val tab: Tab = tab

    fun close() {
        tab.close(this)
    }
}
