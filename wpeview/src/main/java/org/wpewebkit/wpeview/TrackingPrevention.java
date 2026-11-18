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

import android.icu.text.IDNA;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WebKitNetworkSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What tracking prevention noted about the sites that pages load content from, which
 * {@link WebContext#getTrackingPreventionSummary} and {@link WebContext#getTrackingPreventionSiteSummary} give.
 *
 * <p>A site here is what WebKit files the pages of a host under: its registrable domain after the public suffix list,
 * such as {@code example.co.uk} for {@code www.example.co.uk}, or the host itself for an IP address or
 * {@code localhost}.
 */
public final class TrackingPrevention {
    private TrackingPrevention() {}

    /**
     * Another site that the pages of some sites loaded content from, as frames, images or scripts. While tracking
     * prevention is on, it gets none of its cookies in those pages, unless it was allowed to use its data there.
     */
    public static final class ThirdParty {
        private final String domain;
        private final List<FirstParty> firstParties;

        ThirdParty(@NonNull String domain, @NonNull List<FirstParty> firstParties) {
            this.domain = domain;
            this.firstParties = firstParties;
        }

        /** The site, as {@code tracker.example}. */
        public @NonNull String getDomain() { return domain; }

        /**
         * The sites whose pages it was seen in. WebKit counts here the sites that it redirected requests to as well.
         */
        public @NonNull List<FirstParty> getFirstParties() { return firstParties; }

        /** Its entry for the site {@code domain}, or null if it was not seen there. */
        public @Nullable FirstParty getFirstParty(@NonNull String domain) {
            for (FirstParty firstParty : firstParties) {
                if (firstParty.getDomain().equals(domain))
                    return firstParty;
            }
            return null;
        }
    }

    /** A site whose pages a {@link ThirdParty} was seen in. */
    public static final class FirstParty {
        private final String domain;
        private final boolean websiteDataAccessAllowed;
        private final long lastSeenTime;

        FirstParty(@NonNull String domain, boolean websiteDataAccessAllowed, long lastSeenTime) {
            this.domain = domain;
            this.websiteDataAccessAllowed = websiteDataAccessAllowed;
            this.lastSeenTime = lastSeenTime;
        }

        /** The site, as {@code example.com}. */
        public @NonNull String getDomain() { return domain; }

        /**
         * Whether the third party may use its cookies and other data in the pages of this site, as the Storage Access
         * API grants it.
         */
        public boolean isWebsiteDataAccessAllowed() { return websiteDataAccessAllowed; }

        /**
         * When WebKit last noted it in the pages of this site, in milliseconds since the epoch, to the second. WebKit
         * notes what pages load every few seconds, so it may be a little after the load.
         */
        public long getLastSeenTime() { return lastSeenTime; }
    }

    /** The site of an address, and the other sites seen in its pages. */
    public static final class SiteSummary {
        private final String site;
        private final List<ThirdParty> thirdParties;

        SiteSummary(@NonNull String site, @NonNull List<ThirdParty> thirdParties) {
            this.site = site;
            this.thirdParties = thirdParties;
        }

        /** The site, as {@code example.com}, or empty for an address with no host, as {@code about:blank}. */
        public @NonNull String getSite() { return site; }

        /**
         * The other sites seen in its pages, each with all the sites it was seen on, which include this one. WebKit
         * also lists here a site that redirected a request to this one, even if its pages did not load it. Empty for
         * an address with no site.
         */
        public @NonNull List<ThirdParty> getThirdParties() { return thirdParties; }
    }

    private static @NonNull ThirdParty fromWebKit(@NonNull WebKitNetworkSession.ITPThirdParty thirdParty) {
        List<FirstParty> firstParties = new ArrayList<>(thirdParty.firstParties.size());
        for (WebKitNetworkSession.ITPFirstParty firstParty : thirdParty.firstParties)
            firstParties.add(
                new FirstParty(firstParty.domain, firstParty.websiteDataAccessAllowed, firstParty.lastUpdateTime));
        return new ThirdParty(thirdParty.domain, Collections.unmodifiableList(firstParties));
    }

    static @NonNull List<ThirdParty> fromWebKit(@NonNull List<WebKitNetworkSession.ITPThirdParty> thirdParties) {
        List<ThirdParty> result = new ArrayList<>(thirdParties.size());
        for (WebKitNetworkSession.ITPThirdParty thirdParty : thirdParties)
            result.add(fromWebKit(thirdParty));
        return Collections.unmodifiableList(result);
    }

    // Only the third parties seen on the site are made.
    static @NonNull SiteSummary siteSummary(@NonNull String site,
                                            @NonNull List<WebKitNetworkSession.ITPThirdParty> thirdParties) {
        List<ThirdParty> seenOnSite = new ArrayList<>();
        if (!site.isEmpty()) {
            for (WebKitNetworkSession.ITPThirdParty thirdParty : thirdParties) {
                for (WebKitNetworkSession.ITPFirstParty firstParty : thirdParty.firstParties) {
                    if (firstParty.domain.equals(site)) {
                        seenOnSite.add(fromWebKit(thirdParty));
                        break;
                    }
                }
            }
        }
        return new SiteSummary(site, Collections.unmodifiableList(seenOnSite));
    }

    // The processing that WebKit's URL parser gives international host names (URLParser.cpp).
    private static final IDNA IDNA_PROCESSING = IDNA.getUTS46Instance(
        IDNA.CHECK_BIDI | IDNA.CHECK_CONTEXTJ | IDNA.NONTRANSITIONAL_TO_UNICODE | IDNA.NONTRANSITIONAL_TO_ASCII);

    /**
     * The site of {@code url}, as WebKit files its pages under, or empty if it has no host. The address of a blob
     * counts as that of the page that made it, as in WebKit. An international host name counts in its ASCII form, as
     * WebKit gives it, also in an address that a user typed.
     */
    static @NonNull String siteOf(@NonNull String url) {
        Uri uri = Uri.parse(url);
        if ("blob".equalsIgnoreCase(uri.getScheme()) && uri.getSchemeSpecificPart() != null)
            uri = Uri.parse(uri.getSchemeSpecificPart());
        String host = uri.getHost();
        if (host == null)
            return "";
        StringBuilder asciiHost = new StringBuilder();
        IDNA.Info info = new IDNA.Info();
        IDNA_PROCESSING.nameToASCII(host, asciiHost, info);
        if (!info.hasErrors())
            host = asciiHost.toString();
        return WebKitNetworkSession.getRegistrableDomain(host.toLowerCase(Locale.ROOT));
    }
}
