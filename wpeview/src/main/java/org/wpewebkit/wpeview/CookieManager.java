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

package org.wpewebkit.wpeview;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WebKitCookieManager;
import org.wpewebkit.wpe.WebKitWebsiteDataManager;

import java.util.EnumSet;

/**
 * CookieManager handles the cookie policy and storage for a specific {@link WebContext}.
 */
public final class CookieManager {
    private static final CookieAcceptPolicy DEFAULT_POLICY = CookieAcceptPolicy.AcceptNoThirdParty;

    public enum CookieAcceptPolicy {
        AcceptAlways(0),
        AcceptNever(1),
        AcceptNoThirdParty(2);

        private final int value;

        CookieAcceptPolicy(int value) { this.value = value; }

        int getValue() { return value; }
    }

    private final WebKitCookieManager cookieManager;
    private final WebKitWebsiteDataManager websiteDataManager;
    private CookieAcceptPolicy currentPolicy = DEFAULT_POLICY;

    CookieManager(@NonNull WebKitCookieManager cookieManager, @NonNull WebKitWebsiteDataManager websiteDataManager) {
        this.cookieManager = cookieManager;
        this.websiteDataManager = websiteDataManager;
    }

    public void getCookieAcceptPolicy(@Nullable WPECallback<CookieAcceptPolicy> callback) {
        cookieManager.getAcceptPolicy(policy -> {
            currentPolicy = CookieAcceptPolicy.values()[policy];
            if (callback != null)
                callback.onResult(currentPolicy);
        });
    }

    public void setCookieAcceptPolicy(@NonNull CookieAcceptPolicy policy) {
        currentPolicy = policy;
        cookieManager.setAcceptPolicy(policy.getValue());
    }

    public void setAcceptCookie(boolean accept) {
        setCookieAcceptPolicy(accept ? CookieAcceptPolicy.AcceptAlways : CookieAcceptPolicy.AcceptNever);
    }

    @SuppressLint("KotlinPropertyAccess")
    public boolean acceptCookie() {
        return currentPolicy != CookieAcceptPolicy.AcceptNever;
    }

    public void removeAllCookies(@Nullable WPECallback<Boolean> callback) {
        websiteDataManager.clear(EnumSet.of(WebKitWebsiteDataManager.WebsiteDataType.Cookies),
                                 callback != null ? callback::onResult : null);
    }
}
