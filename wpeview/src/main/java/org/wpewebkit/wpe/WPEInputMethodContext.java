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

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * WPEInputMethodContext is a borrowed JNI proxy for the native WPEInputMethodContext that WebKit
 * attaches to a WPEView. The native context is owned by WebKit; this wrapper must not outlive the
 * parent WebKitWebView.
 */
public final class WPEInputMethodContext {
    public interface FocusListener {
        void onFocusIn();
        void onFocusOut();
    }

    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }

    WPEInputMethodContext(long nativePtr) { this.mNativePtr = nativePtr; }

    public void commitText(@NonNull String text) { nativeCommitText(mNativePtr, text); }

    public void deleteSurrounding(int offset, int count) { nativeDeleteSurrounding(mNativePtr, offset, count); }

    public void setFocusListener(@Nullable FocusListener listener) {
        nativeSetFocusListener(mNativePtr, listener != null ? new FocusListenerHolder(listener) : null);
    }

    void invalidate() { mNativePtr = 0; }

    static long getForView(@NonNull WPEView view) { return nativeGetForView(view.getNativePtr()); }

    private static class FocusListenerHolder {
        private final FocusListener delegate;

        FocusListenerHolder(@NonNull FocusListener delegate) { this.delegate = delegate; }

        @Keep
        public void onFocusIn() {
            MainLooperDispatcher.post(delegate::onFocusIn);
        }

        @Keep
        public void onFocusOut() {
            MainLooperDispatcher.post(delegate::onFocusOut);
        }
    }

    private native void nativeCommitText(long nativePtr, String text);
    private native void nativeDeleteSurrounding(long nativePtr, int offset, int count);
    private native void nativeSetFocusListener(long nativePtr, @Nullable FocusListenerHolder listener);
    private static native long nativeGetForView(long viewPtr);
}
