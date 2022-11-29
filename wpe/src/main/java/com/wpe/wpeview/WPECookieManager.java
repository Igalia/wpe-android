/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
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

package com.wpe.wpeview;

import android.annotation.SuppressLint;
import android.os.Handler;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class WPECookieManager {

    private enum NativeCookieAcceptPolicy {
        AcceptAlways(0),
        AcceptNever(1),
        AcceptNoThirdParty(2);

        private final int value;

        NativeCookieAcceptPolicy(int value) { this.value = value; }

        public int getValue() { return value; }
    }

    private static final WPECookieManager instance = new WPECookieManager();

    // Keep these local because fetching them is async in wpe
    private boolean acceptCookie = true;
    private boolean acceptThirdpartyCookie = false;

    private WPECookieManager() {}

    public static @NonNull WPECookieManager getInstance() { return instance; }

    public boolean getAcceptCookie() { return acceptCookie; }

    public void setAcceptCookie(boolean accept) {
        if (acceptCookie == accept)
            return;

        acceptCookie = accept;

        NativeCookieAcceptPolicy policy = NativeCookieAcceptPolicy.AcceptNever;
        if (accept) {
            if (!acceptThirdpartyCookie) {
                policy = NativeCookieAcceptPolicy.AcceptNoThirdParty;
            } else {
                policy = NativeCookieAcceptPolicy.AcceptAlways;
            }
        }

        nativeSetCookieAcceptPolicy(policy.getValue());
    }

    public boolean getAcceptThirdPartyCookies() { return acceptThirdpartyCookie; }

    public void setAcceptThirdPartyCookies(boolean accept) {
        if (acceptThirdpartyCookie == accept)
            return;

        acceptThirdpartyCookie = accept;

        // Update wpe only we accept cookies in general
        if (acceptCookie) {
            NativeCookieAcceptPolicy policy = acceptThirdpartyCookie ? NativeCookieAcceptPolicy.AcceptAlways
                                                                     : NativeCookieAcceptPolicy.AcceptNoThirdParty;
            nativeSetCookieAcceptPolicy(policy.getValue());
        }
    }

    public void removeAllCookies(@Nullable Callback callback) { nativeRemoveAllCookies(new CallbackHolder(callback)); }

    // CookieManagerCallback that calls a Callback on its original thread
    @FunctionalInterface
    public interface Callback {
        void onResult(boolean result);
    }

    private static final class CallbackHolder {
        private final Handler handler = new Handler();
        private final Callback callback;

        public CallbackHolder(@Nullable Callback cb) { callback = cb; }

        @Keep
        public void commitResult(boolean result) {
            if (callback != null) {
                handler.post(() -> callback.onResult(result));
            }
        }
    }

    private static native void nativeSetCookieAcceptPolicy(int policy);
    private static native void nativeRemoveAllCookies(CallbackHolder callbackHolder);
}
