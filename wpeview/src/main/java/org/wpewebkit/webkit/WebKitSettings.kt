package org.wpewebkit.webkit

import com.v7878.foreign.MemorySegment

import org.wpewebkit.glib.GObject
import org.wpewebkit.ffm.*

class WebKitSettings internal constructor(pointer: MemorySegment): GObject(pointer)
{
}
