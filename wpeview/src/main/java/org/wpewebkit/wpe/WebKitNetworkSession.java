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
import androidx.annotation.Nullable;

/**
 * WebKitNetworkSession is an owning JNI proxy for a native WebKitNetworkSession instance.
 * Any derived handles, such as WebKitWebsiteDataManager, are borrowed from the native session state.
 */
public final class WebKitNetworkSession {
    private long mNativePtr = 0;
    private WebKitWebsiteDataManager websiteDataManager;
    public long getNativePtr() { return mNativePtr; }

    public WebKitNetworkSession(@Nullable WebKitWebContext webContext, boolean automationMode, boolean ephemeral,
                                @NonNull String dataDir, @NonNull String cacheDir) {
        mNativePtr = nativeInit(webContext != null ? webContext.getWebKitWebContextPtr() : 0, automationMode, ephemeral,
                                dataDir, cacheDir);
    }

    public void destroy() {
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
        if (websiteDataManager != null) {
            websiteDataManager.invalidate();
            websiteDataManager = null;
        }
    }

    public @NonNull WebKitWebsiteDataManager getWebsiteDataManager() {
        if (websiteDataManager == null) {
            websiteDataManager = new WebKitWebsiteDataManager(nativeWebsiteDataManager(mNativePtr));
        }
        return websiteDataManager;
    }

    public void setTLSErrorsPolicy(int policy) { nativeSetTLSErrorsPolicy(mNativePtr, policy); }

    private native long nativeInit(long contextPtr, boolean automationMode, boolean ephemeral, String dataDir,
                                   String cacheDir);
    private native void nativeDestroy(long nativePtr);
    private native long nativeWebsiteDataManager(long nativePtr);
    private native void nativeSetTLSErrorsPolicy(long nativePtr, int policy);
}
