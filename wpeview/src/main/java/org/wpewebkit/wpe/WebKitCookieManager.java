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
 * WebKitCookieManager is an owning JNI proxy for a native WebKitCookieManager reference.
 * Website-data operations are exposed through WebKitWebsiteDataManager, not this wrapper.
 */
public final class WebKitCookieManager {
    public static final int WEBKIT_COOKIE_POLICY_ACCEPT_ALWAYS = 0;
    public static final int WEBKIT_COOKIE_POLICY_ACCEPT_NEVER = 1;
    public static final int WEBKIT_COOKIE_POLICY_ACCEPT_NO_THIRD_PARTY = 2;

    private long mNativePtr = 0;

    public long getNativePtr() { return mNativePtr; }

    public WebKitCookieManager(@NonNull WebKitNetworkSession networkSession) {
        mNativePtr = nativeInit(networkSession.getNativePtr());
    }

    public void destroy() {
        if (mNativePtr != 0) {
            nativeDestroy(mNativePtr);
            mNativePtr = 0;
        }
    }

    @FunctionalInterface
    public interface AcceptPolicyCallback {
        void onResult(int policy);
    }

    static final class AcceptPolicyCallbackHolder {
        private final AcceptPolicyCallback callback;

        AcceptPolicyCallbackHolder(@Nullable AcceptPolicyCallback callback) { this.callback = callback; }

        @Keep
        void commitResult(int policy) {
            if (callback != null)
                MainLooperDispatcher.post(() -> callback.onResult(policy));
        }
    }

    public void getAcceptPolicy(@Nullable AcceptPolicyCallback callback) {
        if (mNativePtr == 0) {
            if (callback != null)
                MainLooperDispatcher.post(() -> callback.onResult(WEBKIT_COOKIE_POLICY_ACCEPT_ALWAYS));
            return;
        }
        nativeGetAcceptPolicy(mNativePtr, callback != null ? new AcceptPolicyCallbackHolder(callback) : null);
    }

    public void setAcceptPolicy(int policy) { nativeSetAcceptPolicy(mNativePtr, policy); }

    private native long nativeInit(long sessionPtr);
    private native void nativeDestroy(long nativePtr);
    private native void nativeGetAcceptPolicy(long nativePtr, @Nullable AcceptPolicyCallbackHolder callbackHolder);
    private native void nativeSetAcceptPolicy(long nativePtr, int policy);
}
