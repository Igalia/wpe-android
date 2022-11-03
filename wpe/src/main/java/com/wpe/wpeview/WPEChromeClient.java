/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Imanol Fernandez <ifernandez@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
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

package com.wpe.wpeview;

import android.view.View;

import androidx.annotation.NonNull;

public interface WPEChromeClient {
    /**
     * Tell the host application the current progress while loading a page.
     * @param view The WPEView that initiated the callback.
     * @param progress Current page loading progress, represented by
     * an integer between 0 and 100.
     */
    default void onProgressChanged(@NonNull WPEView view, int progress) {}

    /**
     * Notify the host application of a change in the document title.
     * @param view The WPEView that initiated the callback.
     * @param title A String containing the new title of the document.
     */
    default void onReceivedTitle(@NonNull WPEView view, @NonNull String title) {}

    /**
     * Notify the host application that the current page has entered full screen mode.
     * @param view is the View object to be shown.
     * @param callback invoke this callback to request the page to exit
     * full screen mode.
     */
    default void onShowCustomView(@NonNull View view, @NonNull WPEChromeClient.CustomViewCallback callback) {}

    /**
     * Notify the host application that the current page has exited full screen mode.
     */
    default void onHideCustomView() {}

    /**
     * A callback interface used by the host application to notify
     * the current page that its custom view has been dismissed.
     */
    interface CustomViewCallback {
        /**
         * Invoked when the host application dismisses the
         * custom view.
         */
        void onCustomViewHidden();
    }
}
