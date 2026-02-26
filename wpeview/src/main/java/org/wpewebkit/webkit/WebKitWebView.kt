package org.wpewebkit.webkit

import com.v7878.foreign.MemorySegment

import org.wpewebkit.glib.GObject
import org.wpewebkit.ffm.*

class WebKitWebView internal constructor(pointer: MemorySegment) : GObject(pointer)
{
    val canGoBack: Boolean
        get() = fromGBoolean(webkit_web_view_can_go_back(pointer) as Int)
    val canGoForward: Boolean
        get() = fromGBoolean(webkit_web_view_can_go_forward(pointer) as Int)

    fun goBack() = webkit_web_view_go_back(pointer)
    fun goForward() = webkit_web_view_go_forward(pointer)
    fun stopLoading() = webkit_web_view_stop_loading(pointer)

    fun reload(bypassCache: Boolean = false) {
        if (bypassCache)
            webkit_web_view_reload_bypass_cache(pointer)
        else
            webkit_web_view_reload(pointer)
    }

    val title: String?
        get() = toString(webkit_web_view_get_title(pointer) as MemorySegment)
    val uri: String?
        get() = toString(webkit_web_view_get_uri(pointer) as MemorySegment)

    val networkSession: WebKitNetworkSession
        get() = GObject.ref<WebKitNetworkSession>(webkit_web_view_get_network_session(pointer) as MemorySegment)
    val settings: WebKitSettings
        get() = GObject.ref<WebKitSettings>(webkit_web_view_get_settings(pointer) as MemorySegment)
}
