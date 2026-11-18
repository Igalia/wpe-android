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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WebKitNetworkSession is an owning JNI proxy for a native WebKitNetworkSession instance.
 */
public final class WebKitNetworkSession {
    private long mNativePtr = 0;
    private WebKitWebsiteDataManager websiteDataManager;
    public long getNativePtr() { return mNativePtr; }

    public WebKitNetworkSession(@Nullable WebKitWebContext webContext, boolean automationMode, boolean ephemeral,
                                @NonNull String dataDir, @NonNull String cacheDir) {
        if (automationMode && webContext == null)
            throw new IllegalArgumentException("automationMode requires a WebKitWebContext");

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

    public void setITPEnabled(boolean enabled) { nativeSetITPEnabled(mNativePtr, enabled); }

    public boolean isITPEnabled() { return nativeIsITPEnabled(mNativePtr); }

    public boolean isEphemeral() { return nativeIsEphemeral(mNativePtr); }

    /** A site that WebKit's tracking prevention saw in pages of other sites, with those sites. */
    public static final class ITPThirdParty {
        public final @NonNull String domain;
        public final @NonNull List<ITPFirstParty> firstParties;

        ITPThirdParty(@NonNull String domain, @NonNull List<ITPFirstParty> firstParties) {
            this.domain = domain;
            this.firstParties = firstParties;
        }
    }

    /** A site that a third party was seen on, whether it may use its data there, and when it was last seen there. */
    public static final class ITPFirstParty {
        public final @NonNull String domain;
        public final boolean websiteDataAccessAllowed;
        // In milliseconds since the epoch.
        public final long lastUpdateTime;

        ITPFirstParty(@NonNull String domain, boolean websiteDataAccessAllowed, long lastUpdateTime) {
            this.domain = domain;
            this.websiteDataAccessAllowed = websiteDataAccessAllowed;
            this.lastUpdateTime = lastUpdateTime;
        }
    }

    @FunctionalInterface
    public interface ITPSummaryCallback {
        void onResult(@NonNull List<ITPThirdParty> thirdParties);
    }

    // Filled by the native side, one third party after the other, each followed by its first parties.
    static final class ITPSummaryCallbackHolder {
        private final ITPSummaryCallback callback;
        private final List<ITPThirdParty> thirdParties = new ArrayList<>();
        private String domain;
        private List<ITPFirstParty> firstParties;

        ITPSummaryCallbackHolder(@NonNull ITPSummaryCallback callback) { this.callback = callback; }

        private void addPending() {
            if (domain != null)
                thirdParties.add(new ITPThirdParty(domain, Collections.unmodifiableList(firstParties)));
        }

        @Keep
        void addThirdParty(@NonNull String domain) {
            addPending();
            this.domain = domain;
            firstParties = new ArrayList<>();
        }

        @Keep
        void addFirstParty(@NonNull String domain, boolean websiteDataAccessAllowed, long lastUpdateTime) {
            firstParties.add(new ITPFirstParty(domain, websiteDataAccessAllowed, lastUpdateTime));
        }

        // Called back after the native call returns, as on the other paths, so that what the app's callback throws
        // reaches the app.
        @Keep
        void commitResult() {
            addPending();
            domain = null;
            List<ITPThirdParty> result = Collections.unmodifiableList(thirdParties);
            MainLooperDispatcher.postLater(() -> callback.onResult(result));
        }
    }

    /**
     * Gives the sites that tracking prevention saw in pages of other sites to {@code callback}, on the main thread,
     * never before this returns. The list is empty while tracking prevention is off, and always in an ephemeral
     * session, which WebKit is not asked about: it notes nothing there, and asking it would end its network process,
     * whose statistics store asserts that it is never used for an ephemeral session. The check can go once WebKit's
     * WebResourceLoadStatisticsStore::aggregatedThirdPartyData() returns early for them, as its other calls do.
     */
    public void getITPSummary(@NonNull ITPSummaryCallback callback) {
        if (mNativePtr == 0 || isEphemeral()) {
            MainLooperDispatcher.postLater(() -> callback.onResult(Collections.emptyList()));
            return;
        }
        nativeGetITPSummary(mNativePtr, new ITPSummaryCallbackHolder(callback));
    }

    /**
     * The site that tracking prevention files {@code host} under, as WebKit names it: the registrable domain after the
     * public suffix list, the last two labels for an unknown suffix, or the host itself for an IP address, localhost or
     * a single label. The host is lowercase, as in a URL. Empty for an empty host.
     */
    public static @NonNull String getRegistrableDomain(@NonNull String host) {
        return nativeGetRegistrableDomain(host);
    }

    private native long nativeInit(long contextPtr, boolean automationMode, boolean ephemeral, String dataDir,
                                   String cacheDir);
    private native void nativeDestroy(long nativePtr);
    private native long nativeWebsiteDataManager(long nativePtr);
    private native void nativeSetTLSErrorsPolicy(long nativePtr, int policy);
    private native void nativeSetITPEnabled(long nativePtr, boolean enabled);
    private native boolean nativeIsITPEnabled(long nativePtr);
    private native boolean nativeIsEphemeral(long nativePtr);
    private native void nativeGetITPSummary(long nativePtr, @NonNull ITPSummaryCallbackHolder callbackHolder);
    private static native @NonNull String nativeGetRegistrableDomain(@NonNull String host);
}
