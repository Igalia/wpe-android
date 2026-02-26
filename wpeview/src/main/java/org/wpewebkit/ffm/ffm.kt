package org.wpewebkit.ffm

import java.lang.invoke.MethodHandle

import kotlin.reflect.KProperty

import com.v7878.foreign.Linker
import com.v7878.foreign.FunctionDescriptor
import com.v7878.foreign.MemoryLayout
import com.v7878.foreign.MemorySegment
import com.v7878.foreign.SymbolLookup

internal val linker = Linker.nativeLinker()

internal fun getMethod(name: String, descriptor: FunctionDescriptor): MethodHandle
{
    val address = SymbolLookup.loaderLookup().find(name)
    check(address.isPresent) { "Failure to look up symbol '$name'" }
    return linker.downcallHandle(address.get(), descriptor)
}

internal class MethodHandleDelegate(val handle: MethodHandle)
{
    operator fun getValue(self: Any?, property: KProperty<*>) = handle
}

internal class method(val returnType: MemoryLayout, vararg argumentTypes: MemoryLayout)
{
    val arguments = argumentTypes

    operator fun provideDelegate(self: Any?, property: KProperty<*>): MethodHandleDelegate
    {
        return MethodHandleDelegate(getMethod(property.name, FunctionDescriptor.of(returnType, *arguments)))
    }
}

internal class voidMethod(vararg argumentTypes: MemoryLayout)
{
    val arguments = argumentTypes

    operator fun provideDelegate(self: Any?, property: KProperty<*>): MethodHandleDelegate
    {
        return MethodHandleDelegate(getMethod(property.name, FunctionDescriptor.ofVoid(*arguments)))
    }
}

internal fun toString(segment: MemorySegment): String?
{
    if (segment == MemorySegment.NULL)
        return null
    else
        return segment.reinterpret(Long.MAX_VALUE).getString(0)
}
