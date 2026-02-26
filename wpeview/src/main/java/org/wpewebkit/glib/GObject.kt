package org.wpewebkit.glib

import com.v7878.foreign.MemorySegment

import org.wpewebkit.ffm.*

open class GObject(internal val pointer: MemorySegment): AutoCloseable
{
    companion object
    {
        inline fun<reified T: GObject> ref(pointer: MemorySegment): T {
            g_object_ref_sink(pointer)
            return T::class.java.getConstructor(MemorySegment::class.java).newInstance(pointer)
        }

        inline fun<reified T: GObject> adopt(pointer: MemorySegment): T {
            if (fromGBoolean(g_object_is_floating(pointer) as Int))
                g_object_ref_sink(pointer)
            return T::class.java.getConstructor(MemorySegment::class.java).newInstance(pointer)
        }
    }

    init {
        require(!fromGBoolean(g_object_is_floating(pointer) as Int)) {
            "Wrapper GObject must not be floating"
        }
    }

    override fun close() {
        g_object_unref(pointer)
    }
}
