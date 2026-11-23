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

import androidx.annotation.NonNull;

import org.wpewebkit.wpe.WebKitWebsiteDataManager;

import java.util.EnumSet;
import java.util.Set;

/**
 * The kinds of data that WebKit keeps for the sites that the pages of a {@link WebContext} load, which
 * {@link WebContext#clearWebsiteData} and {@link WebContext#clearWebsiteDataForSite} clear. For example, a browser that
 * offers to clear the cached images and files clears {@link #Cache}, and one that offers to clear the cookies and site
 * data clears all the others.
 */
public enum WebsiteDataType {
    /**
     * The copies of the pages and of the resources that they loaded, as images, scripts and style sheets, which load
     * them again without the network while they are valid. WebKit keeps them in memory, and also on disk for a session
     * that keeps its data.
     */
    Cache(WebKitWebsiteDataManager.WebsiteDataType.MemoryCache, WebKitWebsiteDataManager.WebsiteDataType.DiskCache),
    /**
     * Cookies. With them go the keys that give each site its own identifiers for the cameras and microphones of the
     * device, which pages read with {@code navigator.mediaDevices}, as cookies would let sites match them.
     */
    Cookies(WebKitWebsiteDataManager.WebsiteDataType.Cookies,
            WebKitWebsiteDataManager.WebsiteDataType.DeviceIDHashSalt),
    /** What scripts keep with {@code localStorage}. */
    LocalStorage(WebKitWebsiteDataManager.WebsiteDataType.LocalStorage),
    /** What scripts keep with {@code sessionStorage}, for as long as their tab is open. */
    SessionStorage(WebKitWebsiteDataManager.WebsiteDataType.SessionStorage),
    /** The IndexedDB databases that scripts keep. */
    IndexedDB(WebKitWebsiteDataManager.WebsiteDataType.IndexedDBDatabases),
    /** The responses that scripts and service workers keep with the Cache API, {@code caches} in scripts. */
    CacheStorage(WebKitWebsiteDataManager.WebsiteDataType.DOMCache),
    /** The service workers that sites registered, which load their pages again without the network. */
    ServiceWorkers(WebKitWebsiteDataManager.WebsiteDataType.ServiceWorkerRegistrations),
    /** The files that scripts keep in the private file system of their site, {@code navigator.storage.getDirectory}. */
    FileSystem(WebKitWebsiteDataManager.WebsiteDataType.FileSystem),
    /**
     * The hosts that asked to be loaded over https only, with the {@code Strict-Transport-Security} header, which pages
     * then load over https even from an http address.
     */
    HSTS(WebKitWebsiteDataManager.WebsiteDataType.HSTSCache);

    private final EnumSet<WebKitWebsiteDataManager.WebsiteDataType> webKitTypes;

    WebsiteDataType(@NonNull WebKitWebsiteDataManager.WebsiteDataType first,
                    @NonNull WebKitWebsiteDataManager.WebsiteDataType... rest) {
        webKitTypes = EnumSet.of(first, rest);
    }

    static @NonNull EnumSet<WebKitWebsiteDataManager.WebsiteDataType> toWebKit(@NonNull Set<WebsiteDataType> types) {
        EnumSet<WebKitWebsiteDataManager.WebsiteDataType> webKitTypes =
            EnumSet.noneOf(WebKitWebsiteDataManager.WebsiteDataType.class);
        for (WebsiteDataType type : types)
            webKitTypes.addAll(type.webKitTypes);
        return webKitTypes;
    }
}
