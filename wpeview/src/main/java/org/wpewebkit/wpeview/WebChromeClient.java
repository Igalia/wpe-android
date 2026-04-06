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

import android.view.View;

import androidx.annotation.NonNull;

/**
 * WebChromeClient provides the high-level chrome and UI callbacks for {@link WebView}.
 */
public class WebChromeClient {
    public void onCloseWindow(@NonNull WebView window) {}
    public void onProgressChanged(@NonNull WebView view, int newProgress) {}
    public void onReceivedTitle(@NonNull WebView view, @NonNull String title) {}

    public interface CustomViewCallback {
        void onCustomViewHidden();
    }

    // FIXME: wire WebKit enter-fullscreen / leave-fullscreen signals to invoke these callbacks.
    public void onShowCustomView(@NonNull View view, @NonNull CustomViewCallback callback) {}
    public void onHideCustomView() {}
}
