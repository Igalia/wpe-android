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

package org.wpewebkit.wpeview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WKCookieManager;
import org.wpewebkit.wpe.WKNetworkSession;
import org.wpewebkit.wpe.WKWebsiteDataManager;

import java.util.EnumSet;

public final class WPECookieManager {

    public enum CookieAcceptPolicy {
        AcceptAlways(0),
        AcceptNever(1),
        AcceptNoThirdParty(2);

        private final int value;

        CookieAcceptPolicy(int value) { this.value = value; }

        int getValue() { return value; }
    }

    @FunctionalInterface
    public interface Callback {
        void onResult(boolean result);
    }

    private WKNetworkSession networkSession;
    private WKCookieManager cookieManager;
    private WKWebsiteDataManager websiteDataManager;

    WPECookieManager(WKNetworkSession networkSession) {
        this.networkSession = networkSession;
        this.cookieManager = networkSession.getCookieManager();
        this.websiteDataManager = networkSession.getWebsiteDataManager();
    }

    public @NonNull CookieAcceptPolicy getCookieAcceptPolicy() {
        return CookieAcceptPolicy.values()[cookieManager.getCookieAcceptPolicy().ordinal()];
    }

    public void setCookieAcceptPolicy(@NonNull CookieAcceptPolicy policy) {
        cookieManager.setCookieAcceptPolicy(WKCookieManager.CookieAcceptPolicy.values()[policy.ordinal()]);
    }

    public void removeAllCookies(@Nullable Callback callback) {
        EnumSet<WKWebsiteDataManager.WebsiteDataType> valuesToClear =
            EnumSet.of(WKWebsiteDataManager.WebsiteDataType.Cookies);
        WKWebsiteDataManager.Callback callbackImpl = callback == null ? null : callback::onResult;
        websiteDataManager.clear(valuesToClear, callbackImpl);
    }
}
