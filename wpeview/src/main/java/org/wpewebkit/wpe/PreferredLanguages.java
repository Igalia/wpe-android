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

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Gives WebKit the languages of the device, and follows them when the user changes them. Pages get the first one in
 * {@code navigator.languages}, and all of them in the {@code Accept-Language} header.
 *
 * <p>Unless told otherwise, WebKit takes them from the locale of the process, which on Android is C.UTF-8, and pages
 * get the language "C". WebKit keeps one list of languages for the whole process, and starts new web processes with
 * it, but it only tells the running processes of the web context it gets the list from. So this class follows the
 * device once for the process, and gives a new list to every web context still alive. It holds them weakly, so a web
 * context that is never destroyed is not kept alive by it.
 */
public final class PreferredLanguages {
    // The language WebKit's own fallback gives the C locale.
    private static final String FALLBACK_LANGUAGE = "en-US";

    private static final Set<WebKitWebContext> contexts = Collections.newSetFromMap(new WeakHashMap<>());
    // The languages WebKit has, or null before the first web context gives it any.
    private static @Nullable String[] languages;
    private static boolean followingDevice;

    private PreferredLanguages() {}

    /** Gives a new web context the languages of the device, and keeps them up to date. */
    @MainThread
    static void add(@NonNull Context applicationContext, @NonNull WebKitWebContext context) {
        contexts.add(context);
        if (!followingDevice) {
            followingDevice = true;
            applicationContext.registerComponentCallbacks(new ComponentCallbacks() {
                @Override
                public void onConfigurationChanged(@NonNull Configuration newConfig) {
                    update(newConfig.getLocales());
                }

                @Override
                public void onLowMemory() {}
            });
        }
        update(applicationContext.getResources().getConfiguration().getLocales());
    }

    /** Stops giving languages to a web context, before it is destroyed. */
    @MainThread
    static void remove(@NonNull WebKitWebContext context) {
        contexts.remove(context);
    }

    // Package-private: called from the ComponentCallbacks inner class (avoids a synthetic accessor).
    static void update(@NonNull LocaleList locales) {
        String[] tags = toLanguageTags(locales);
        // With no web context there is nobody to give them to; the next one gets them.
        if (contexts.isEmpty() || Arrays.equals(tags, languages))
            return;
        languages = tags;
        for (WebKitWebContext context : new ArrayList<>(contexts))
            context.setPreferredLanguages(tags);
    }

    /**
     * The BCP 47 tags of the locales, in order and without repeats, or {@code en-US} when there are none. They leave
     * out the Unicode extensions Android adds for regional preferences, such as {@code -u-fw-mon} for the first day of
     * the week, which name no language.
     */
    @VisibleForTesting
    @NonNull
    public static String[] toLanguageTags(@NonNull LocaleList locales) {
        Set<String> tags = new LinkedHashSet<>();
        for (int i = 0; i < locales.size(); i++) {
            String tag = locales.get(i).stripExtensions().toLanguageTag();
            if (!tag.equals("und"))
                tags.add(tag);
        }
        if (tags.isEmpty())
            tags.add(FALLBACK_LANGUAGE);
        return tags.toArray(new String[0]);
    }
}
