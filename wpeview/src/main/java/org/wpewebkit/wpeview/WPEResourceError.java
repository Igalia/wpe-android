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

/**
 * Why a page failed to load, as passed to {@link WebViewClient#onReceivedError}.
 */
public class WPEResourceError {
    private final int errorCode;
    private final String description;

    public WPEResourceError(int errorCode, @NonNull String description) {
        this.errorCode = errorCode;
        this.description = description;
    }

    /**
     * Gets the error code, one of the {@code ERROR_*} constants of {@link WebViewClient}.
     *
     * @return The error code.
     */
    public int getErrorCode() { return errorCode; }

    /**
     * Gets WebKit's description of the error, in English.
     *
     * @return The description of the error.
     */
    public @NonNull String getDescription() { return description; }
}
