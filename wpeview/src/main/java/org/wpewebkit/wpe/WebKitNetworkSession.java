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
    protected long nativePtr = 0;
    private WebKitWebsiteDataManager websiteDataManager;
    public long getNativePtr() { return nativePtr; }

    public WebKitNetworkSession(@Nullable WebKitWebContext webContext, boolean automationMode, boolean ephemeral,
                                @NonNull String dataDir, @NonNull String cacheDir) {
        nativePtr = nativeInit(webContext != null ? webContext.getNativePtr() : 0, automationMode, ephemeral, dataDir,
                               cacheDir);
    }

    public void destroy() {
        if (nativePtr != 0) {
            if (websiteDataManager != null) {
                websiteDataManager.invalidate();
                websiteDataManager = null;
            }
            nativeDestroy(nativePtr);
            nativePtr = 0;
        }
    }

    // TLS errors policy values mirror WebKitTLSErrorsPolicy:
    // 0 = WEBKIT_TLS_ERRORS_POLICY_IGNORE
    // 1 = WEBKIT_TLS_ERRORS_POLICY_FAIL
    public void setTLSErrorsPolicy(int policy) { nativeSetTLSErrorsPolicy(nativePtr, policy); }

    public @NonNull WebKitWebsiteDataManager getWebsiteDataManager() {
        if (websiteDataManager == null)
            websiteDataManager = new WebKitWebsiteDataManager(nativeWebsiteDataManager(nativePtr));
        return websiteDataManager;
    }

    private native long nativeInit(long webContextPtr, boolean automationMode, boolean ephemeral, String dataDir,
                                   String cacheDir);
    private native void nativeDestroy(long nativePtr);
    private native long nativeWebsiteDataManager(long nativePtr);
    private native void nativeSetTLSErrorsPolicy(long nativePtr, int policy);
}
