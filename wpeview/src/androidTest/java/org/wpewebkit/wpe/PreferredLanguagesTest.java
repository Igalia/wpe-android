package org.wpewebkit.wpe;

import android.os.LocaleList;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertArrayEquals;

/**
 * The languages WebKit gets from the locales of the device: BCP 47 tags without the regional preferences Android adds
 * to them, and never an empty list.
 */
@RunWith(AndroidJUnit4.class)
public class PreferredLanguagesTest {
    private static String[] tags(String locales) {
        return PreferredLanguages.toLanguageTags(LocaleList.forLanguageTags(locales));
    }

    @Test
    public void testKeepsTheOrderOfTheLocales() {
        assertArrayEquals(new String[] {"fr-CA", "en-GB", "zh-Hans-CN"}, tags("fr-CA,en-GB,zh-Hans-CN"));
    }

    @Test
    public void testLeavesOutRegionalPreferences() {
        // The first day of the week and the temperature unit, as Android 14 adds them.
        assertArrayEquals(new String[] {"en-US", "de-DE"}, tags("en-US-u-fw-mon-mu-celsius,de-DE-u-fw-mon"));
    }

    @Test
    public void testLeavesOutRepeats() {
        // Two locales that only differ in their regional preferences name the same language.
        assertArrayEquals(new String[] {"en-US", "fr-FR"}, tags("en-US-u-fw-mon,fr-FR,en-US"));
    }

    @Test
    public void testFallsBackToEnglishWithoutLanguages() {
        assertArrayEquals(new String[] {"en-US"}, tags(""));
        assertArrayEquals(new String[] {"en-US"}, tags("und"));
    }
}
