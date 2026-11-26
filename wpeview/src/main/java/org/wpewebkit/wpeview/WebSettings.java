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

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.WebKitSettings;
import org.wpewebkit.wpe.WebKitWebView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * WebSettings provides control over the configuration of a {@link WebPage}, and of the page a {@link WebView} shows.
 * Once the page is destroyed, the settings keep the values they had; they can still be read and changed, but changes
 * have no effect.
 */
public class WebSettings {
    /** Android's Autofill fills the login fields of the page and may offer to save its sign ins. */
    public static final int AUTOFILL_FILL_AND_SAVE = 0;
    /**
     * Android's Autofill fills the login fields of the page but never offers to save its sign ins: it hears of the
     * site of a login field when the user enters it, never of the values of the fields. So its list of accounts does
     * not narrow as the user types.
     */
    public static final int AUTOFILL_FILL_ONLY = 1;
    /** Android's Autofill hears nothing of the page, and what users type stays in the web process. */
    public static final int AUTOFILL_OFF = 2;

    @IntDef({AUTOFILL_FILL_AND_SAVE, AUTOFILL_FILL_ONLY, AUTOFILL_OFF})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AutofillMode {}

    // The settings whose text zoom follows the font size of the device: those whose app has not set one.
    private static final Set<WebSettings> followingFontScale = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean listeningToFontScale;

    // Null once the page is destroyed; the fields then hold the values.
    private @Nullable WebKitSettings settings;
    private @Nullable WebKitWebView webView;
    private @NonNull String userAgent = "";
    private boolean mediaPlaybackRequiresUserGesture;
    private boolean allowUniversalAccessFromFileURLs;
    private boolean allowFileAccessFromFileURLs;
    private boolean developerExtrasEnabled;
    private boolean disableWebSecurity;
    private boolean audiblePlaybackRequiresPageVisibility;
    // Not a WebKit setting: the WebView reads it for the input method.
    private boolean personalizedLearningEnabled = true;
    // Not a WebKit setting either: the WebView reads it for Android's Autofill, and hears of each change through the
    // page, until it is destroyed.
    private @AutofillMode int autofillMode = AUTOFILL_FILL_AND_SAVE;
    private @Nullable Runnable autofillModeListener;
    // Kept here also while the page lives. It is the zoom level of the web view, whose settings have zoom text only
    // on, and WebKit's is 100%.
    private int textZoom = 100;

    @MainThread
    WebSettings(@NonNull WebKitSettings settings, @NonNull WebKitWebView webView, @NonNull Context applicationContext,
                @NonNull Runnable autofillModeListener) {
        this.settings = settings;
        this.webView = webView;
        this.autofillModeListener = autofillModeListener;
        followFontScale(applicationContext);
    }

    // The font size is the same for the whole app, so the application context tells all its changes, also to the
    // pages of views that are out of their window.
    @MainThread
    private void followFontScale(@NonNull Context applicationContext) {
        listenToFontScale(applicationContext);
        followingFontScale.add(this);
        applyTextZoom(textZoomOf(applicationContext.getResources().getConfiguration()));
    }

    // Static, so that the callbacks, which the application keeps for good, hold no settings.
    @MainThread
    private static void listenToFontScale(@NonNull Context applicationContext) {
        if (listeningToFontScale)
            return;
        listeningToFontScale = true;
        applicationContext.registerComponentCallbacks(new ComponentCallbacks() {
            @Override
            public void onConfigurationChanged(@NonNull Configuration newConfig) {
                fontScaleChanged(newConfig);
            }

            @Override
            public void onLowMemory() {}
        });
    }

    // Package-private: called from the ComponentCallbacks inner class (avoids a synthetic accessor).
    static void fontScaleChanged(@NonNull Configuration configuration) {
        int textZoom = textZoomOf(configuration);
        for (WebSettings settings : new ArrayList<>(followingFontScale))
            settings.applyTextZoom(textZoom);
    }

    // A configuration may leave the font scale undefined, as 0.
    private static int textZoomOf(@NonNull Configuration configuration) {
        return configuration.fontScale > 0 ? Math.round(configuration.fontScale * 100) : 100;
    }

    private void applyTextZoom(int textZoom) {
        if (textZoom == this.textZoom)
            return;
        this.textZoom = textZoom;
        if (webView != null)
            webView.setZoomLevel(textZoom / 100.0);
    }

    // Called by WebPage.destroy() before it destroys the WebKitSettings.
    void detach() {
        if (settings == null)
            return;
        userAgent = settings.getUserAgentString();
        mediaPlaybackRequiresUserGesture = settings.getMediaPlaybackRequiresUserGesture();
        allowUniversalAccessFromFileURLs = settings.getAllowUniversalAccessFromFileURLs();
        allowFileAccessFromFileURLs = settings.getAllowFileAccessFromFileURLs();
        developerExtrasEnabled = settings.getDeveloperExtrasEnabled();
        disableWebSecurity = settings.getDisableWebSecurity();
        audiblePlaybackRequiresPageVisibility = settings.getAudiblePlaybackRequiresPageVisibility();
        settings = null;
        webView = null;
        autofillModeListener = null;
        followingFontScale.remove(this);
    }

    public @NonNull String getUserAgentString() { return settings != null ? settings.getUserAgentString() : userAgent; }
    public void setUserAgentString(@NonNull String userAgent) {
        if (settings != null)
            settings.setUserAgentString(userAgent);
        else
            this.userAgent = userAgent;
    }

    public boolean getMediaPlaybackRequiresUserGesture() {
        return settings != null ? settings.getMediaPlaybackRequiresUserGesture() : mediaPlaybackRequiresUserGesture;
    }
    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        if (settings != null)
            settings.setMediaPlaybackRequiresUserGesture(require);
        else
            mediaPlaybackRequiresUserGesture = require;
    }

    public boolean getAllowUniversalAccessFromFileURLs() {
        return settings != null ? settings.getAllowUniversalAccessFromFileURLs() : allowUniversalAccessFromFileURLs;
    }
    public void setAllowUniversalAccessFromFileURLs(boolean flag) {
        if (settings != null)
            settings.setAllowUniversalAccessFromFileURLs(flag);
        else
            allowUniversalAccessFromFileURLs = flag;
    }

    public boolean getAllowFileAccessFromFileURLs() {
        return settings != null ? settings.getAllowFileAccessFromFileURLs() : allowFileAccessFromFileURLs;
    }
    public void setAllowFileAccessFromFileURLs(boolean flag) {
        if (settings != null)
            settings.setAllowFileAccessFromFileURLs(flag);
        else
            allowFileAccessFromFileURLs = flag;
    }

    public boolean getDeveloperExtrasEnabled() {
        return settings != null ? settings.getDeveloperExtrasEnabled() : developerExtrasEnabled;
    }
    public void setDeveloperExtrasEnabled(boolean flag) {
        if (settings != null)
            settings.setDeveloperExtrasEnabled(flag);
        else
            developerExtrasEnabled = flag;
    }

    public boolean getDisableWebSecurity() {
        return settings != null ? settings.getDisableWebSecurity() : disableWebSecurity;
    }
    public void setDisableWebSecurity(boolean disable) {
        if (settings != null)
            settings.setDisableWebSecurity(disable);
        else
            disableWebSecurity = disable;
    }

    public boolean getAudiblePlaybackRequiresPageVisibility() {
        return settings != null ? settings.getAudiblePlaybackRequiresPageVisibility()
                                : audiblePlaybackRequiresPageVisibility;
    }
    /**
     * Whether the page plays audible media only while it is visible. When the page is hidden, for example
     * because its WebView is out of the window like a tab that is not shown, or the activity is stopped, its
     * audible media pauses, and resumes when the page is shown again; a hidden page cannot start it either.
     * This covers audio and video elements, not Web Audio. WebKit reads the setting when it creates a media
     * element, so set it before loading the page: the media elements a page already has keep the value they
     * were created with. The default is false: the media keeps playing.
     */
    public void setAudiblePlaybackRequiresPageVisibility(boolean require) {
        if (settings != null)
            settings.setAudiblePlaybackRequiresPageVisibility(require);
        else
            audiblePlaybackRequiresPageVisibility = require;
    }

    public boolean getPersonalizedLearningEnabled() { return personalizedLearningEnabled; }
    /**
     * Whether the input method may learn from what the user types in the page's fields, such as new words for its
     * suggestions. When false, the view that shows the page asks every input method not to, with
     * {@link android.view.inputmethod.EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING}, as a private browsing mode
     * needs: for the fields of the page, and for the answer to a {@code prompt()} dialog that the view shows itself.
     * The input method reads it when it connects to a field, as when the field gets the focus, so set it before the
     * page loads: a field that has the focus may keep what it had. Password fields never allow it. Unlike the other
     * settings, it passes to the page of a window that the page opens, which starts with the value of its opener, so
     * that the windows of a private page are private too. The default is true.
     */
    public void setPersonalizedLearningEnabled(boolean enabled) { personalizedLearningEnabled = enabled; }

    public @AutofillMode int getAutofillMode() { return autofillMode; }
    /**
     * What Android's Autofill may do with the login fields of the page, which the view that shows it gives to the
     * password service the user chose: fill them and offer to save the sign ins, {@link #AUTOFILL_FILL_AND_SAVE};
     * fill them but never offer to save, {@link #AUTOFILL_FILL_ONLY}, as a private browsing mode needs, since a saved
     * login would keep the visit; or nothing, {@link #AUTOFILL_OFF}. A change applies at once: Android forgets what it
     * heard of the page, which ends the one Autofill session of the activity, also for the other views of it, and
     * hears again of a login field that has the focus. Like
     * {@link #setPersonalizedLearningEnabled}, it passes to the page of a window that the page opens. A view that is
     * not important for Autofill gives Android nothing of any page. The default is {@link #AUTOFILL_FILL_AND_SAVE}.
     *
     * @throws IllegalArgumentException if mode is none of the three
     */
    @MainThread
    public void setAutofillMode(@AutofillMode int mode) {
        if (mode != AUTOFILL_FILL_AND_SAVE && mode != AUTOFILL_FILL_ONLY && mode != AUTOFILL_OFF)
            throw new IllegalArgumentException("Unknown Autofill mode " + mode);
        if (mode == autofillMode)
            return;
        autofillMode = mode;
        if (autofillModeListener != null)
            autofillModeListener.run();
    }

    /**
     * The text zoom of the page in percent. Until the app sets one with {@link #setTextZoom}, it is the font size
     * that the user chose in Android's settings, the {@link Configuration#fontScale} of the application times 100, and
     * it changes with it. An activity that overrides its own font scale does not change it: the app sets the text zoom
     * then. The settings of a destroyed page no longer follow the font size, and those of the page of a window that a
     * page opens follow it from the start, until the app sets theirs, for example in
     * {@link WebPageChromeClient#onCreateWindow} or {@link WebChromeClient#onCreateWindow}.
     */
    @MainThread
    public int getTextZoom() {
        return textZoom;
    }
    /**
     * Sets the text zoom of the page in percent: 100 shows the text at the size the page gives it, 150 makes it half
     * again as big. Only the text and what the page sizes after it change; the width of the page, its images and what
     * it sizes in pixels stay, so the text wraps to the width of the view. The zoom applies at once, and to everything
     * the page loads after. From then on the text zoom no longer follows the font size of the device, and there is no
     * way back to it.
     *
     * @throws IllegalArgumentException if textZoom is 0 or less, which Android's WebSettings takes
     */
    @MainThread
    public void setTextZoom(int textZoom) {
        if (textZoom <= 0)
            throw new IllegalArgumentException("The text zoom must be more than 0, not " + textZoom);
        followingFontScale.remove(this);
        applyTextZoom(textZoom);
    }
}
