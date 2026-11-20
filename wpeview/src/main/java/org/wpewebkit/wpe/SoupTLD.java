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
 * The public suffix list of libsoup, which WebKit uses to tell sites apart.
 */
public final class SoupTLD {
    private SoupTLD() {}

    public static boolean domainIsPublicSuffix(@NonNull String domain) { return nativeDomainIsPublicSuffix(domain); }

    private static native boolean nativeDomainIsPublicSuffix(@NonNull String domain);
}
