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

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The public suffix list names suffixes of every kind: top level domains, the domains under them that the list names,
 * those that a wildcard rule names and the private ones. Any other domain is not a public suffix.
 */
@RunWith(AndroidJUnit4.class)
public class PublicSuffixListTest {
    @Test
    public void namesPublicSuffixes() {
        assertAll(true, "com", "co.uk", "co.za", "github.io", "ck", "foo.ck", "рф", "xn--p1ai", "COM", "Co.Uk", "com.");
    }

    @Test
    public void namesNothingElse() {
        assertAll(false, "za", "www.ck", "example.com", "www.github.io", "txt", "notatld", "", ".", "com..",
                  "192.168.1.1", "com\u0000", "com\u0000example", "com\uD800");
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void refusesNull() {
        assertThrows(NullPointerException.class, () -> PublicSuffixList.isPublicSuffix(null));
    }

    private static void assertAll(boolean expected, String... domains) {
        List<String> wrong = new ArrayList<>();
        for (String domain : domains) {
            if (PublicSuffixList.isPublicSuffix(domain) != expected)
                wrong.add("\"" + domain + "\"");
        }
        assertTrue("Expected " + expected + " for " + String.join(", ", wrong), wrong.isEmpty());
    }
}
