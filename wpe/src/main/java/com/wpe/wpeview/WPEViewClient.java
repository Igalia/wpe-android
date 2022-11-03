/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Imanol Fernandez <ifernandez@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

import androidx.annotation.NonNull;

public interface WPEViewClient {
    /**
     * Notify the host application that a page has started loading. This method
     * is called once for each main frame load so a page with iframes or
     * framesets will call onPageStarted one time for the main frame. This also
     * means that onPageStarted will not be called when the contents of an
     * embedded frame changes, i.e. clicking a link whose target is an iframe.
     * @param view The WPEView that is initiating the callback.
     * @param url The url to be loaded.
     */
    default void onPageStarted(@NonNull WPEView view, @NonNull String url) {}

    /**
     * Notify the host application that a page has finished loading. This method
     * is called only for main frame.
     * @param view The WPEView that is initiating the callback.
     * @param url The url of the page.
     */
    default void onPageFinished(@NonNull WPEView view, @NonNull String url) {}

    /**
     * Notify the host application that the internal SurfaceView has been created
     * and it's ready to render to it's surface.
     */
    default void onViewReady(@NonNull WPEView view) {}
}
