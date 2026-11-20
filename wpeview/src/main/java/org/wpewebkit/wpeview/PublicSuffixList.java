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

import org.wpewebkit.wpe.SoupTLD;

import java.util.Objects;

/**
 * The public suffix list: the domains under which anyone can register a name of their own, such as {@code com},
 * {@code co.uk} or {@code github.io}. WebKit uses the same list to tell sites apart, for example for cookies.
 */
public final class PublicSuffixList {
    private PublicSuffixList() {}

    /**
     * Whether the list names {@code domain} as a public suffix. A domain that the list does not name is not one, so a
     * top level domain that does not exist is not a public suffix. Case and a final dot do not matter, and an
     * international domain can be given in Unicode or in its ASCII form, as {@code рф} or {@code xn--p1ai}.
     */
    public static boolean isPublicSuffix(@NonNull String domain) {
        Objects.requireNonNull(domain, "domain");
        return SoupTLD.domainIsPublicSuffix(domain);
    }
}
