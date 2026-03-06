/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.wpe;

/**
 * WPEScreen is a borrowed JNI proxy for a native WPEScreen instance owned by WPEDisplay.
 */
public final class WPEScreen {
    protected long nativePtr = 0;

    WPEScreen(long nativePtr) { this.nativePtr = nativePtr; }

    void invalidate() { nativePtr = 0; }

    public float getScale() { return nativePtr != 0 ? nativeGetScale(nativePtr) : 1.0f; }

    public void setScale(float scale) {
        if (nativePtr != 0)
            nativeSetScale(nativePtr, scale);
    }

    private native float nativeGetScale(long nativePtr);
    private native void nativeSetScale(long nativePtr, float scale);
}
