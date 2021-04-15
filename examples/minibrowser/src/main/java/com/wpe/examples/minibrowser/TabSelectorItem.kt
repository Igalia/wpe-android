package com.wpe.examples.minibrowser

class TabSelectorItem(tab: Tab) {
    internal val tab: Tab = tab

    fun close() {
        tab.close(this)
    }
}