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

import androidx.annotation.NonNull;

/**
 * WebKitSettings is an owning JNI proxy for a native WebKitSettings instance.
 */
public final class WebKitSettings {
    private long mNativePtr = 0;
    public long getNativePtr() { return mNativePtr; }

    public WebKitSettings() { mNativePtr = nativeInit(); }

    public void destroy() {
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
    }

    public @NonNull String getUserAgentString() { return nativeGetUserAgentString(mNativePtr); }
    public void setUserAgentString(@NonNull String userAgent) { nativeSetUserAgentString(mNativePtr, userAgent); }

    public boolean getMediaPlaybackRequiresUserGesture() {
        return nativeGetMediaPlaybackRequiresUserGesture(mNativePtr);
    }
    public void setMediaPlaybackRequiresUserGesture(boolean requires) {
        nativeSetMediaPlaybackRequiresUserGesture(mNativePtr, requires);
    }

    public boolean getAllowUniversalAccessFromFileURLs() {
        return nativeGetAllowUniversalAccessFromFileURLs(mNativePtr);
    }
    public void setAllowUniversalAccessFromFileURLs(boolean flag) {
        nativeSetAllowUniversalAccessFromFileURLs(mNativePtr, flag);
    }

    public boolean getAllowFileAccessFromFileURLs() { return nativeGetAllowFileAccessFromFileURLs(mNativePtr); }
    public void setAllowFileAccessFromFileURLs(boolean flag) { nativeSetAllowFileAccessFromFileURLs(mNativePtr, flag); }

    public boolean getDeveloperExtrasEnabled() { return nativeGetDeveloperExtrasEnabled(mNativePtr); }
    public void setDeveloperExtrasEnabled(boolean flag) { nativeSetDeveloperExtrasEnabled(mNativePtr, flag); }

    public boolean getDisableWebSecurity() { return nativeGetDisableWebSecurity(mNativePtr); }
    public void setDisableWebSecurity(boolean disable) { nativeSetDisableWebSecurity(mNativePtr, disable); }

    private native long nativeInit();
    private native void nativeDestroy(long nativePtr);
    private native String nativeGetUserAgentString(long nativePtr);
    private native void nativeSetUserAgentString(long nativePtr, String userAgent);
    private native boolean nativeGetMediaPlaybackRequiresUserGesture(long nativePtr);
    private native void nativeSetMediaPlaybackRequiresUserGesture(long nativePtr, boolean requires);
    private native boolean nativeGetAllowUniversalAccessFromFileURLs(long nativePtr);
    private native void nativeSetAllowUniversalAccessFromFileURLs(long nativePtr, boolean flag);
    private native boolean nativeGetAllowFileAccessFromFileURLs(long nativePtr);
    private native void nativeSetAllowFileAccessFromFileURLs(long nativePtr, boolean flag);
    private native boolean nativeGetDeveloperExtrasEnabled(long nativePtr);
    private native void nativeSetDeveloperExtrasEnabled(long nativePtr, boolean flag);
    private native boolean nativeGetDisableWebSecurity(long nativePtr);
    private native void nativeSetDisableWebSecurity(long nativePtr, boolean disable);
}
