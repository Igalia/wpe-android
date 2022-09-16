/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
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

import android.view.SurfaceHolder;

public interface SurfaceClient {
    /**
     * Notify the host application to add a callback used to process Surface creation and update events.
     *
     * @param view The WPEView that initiated the callback.
     * @param callback The SurfaceHolder.Callback2 to let the host application send Surface creation and update events.
     */
    void addCallback(WPEView view, SurfaceHolder.Callback2 callback);

    /**
     * Notify the host application to remove a callback,
     *
     * @param view The WPEView that initiated the callback.
     * @param callback A SurfaceHolder.Callback2 to be removed.
     */
    void removeCallback(WPEView view, SurfaceHolder.Callback2 callback);
}
