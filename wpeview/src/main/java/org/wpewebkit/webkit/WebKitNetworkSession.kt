package org.wpewebkit.webkit

import com.v7878.foreign.MemorySegment

import org.wpewebkit.glib.GObject
import org.wpewebkit.ffm.*

class WebKitNetworkSession internal constructor(pointer: MemorySegment): GObject(pointer)
{
    companion object {
        val default: WebKitNetworkSession
            get() = GObject.ref<WebKitNetworkSession>(webkit_network_session_get_default() as MemorySegment)

        fun createEphemeral() = GObject.adopt<WebKitNetworkSession>(webkit_network_session_new_ephemeral() as MemorySegment)
    }

    var itpEnabled: Boolean
        get() = fromGBoolean(webkit_network_session_get_itp_enabled(pointer) as Int)
        set(enabled) {
            webkit_network_session_set_itp_enabled(pointer, toGBoolean(enabled))
        }
}
