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

package org.wpewebkit.tools.minibrowser

import android.content.Context
import android.text.TextUtils
import org.json.JSONObject
import org.wpewebkit.wpeview.WebViewClient

/**
 * The page shown with WebView.loadAlternateHtml in place of a page whose web process ended, as Epiphany does. Its
 * Reload button loads the page again with location.replace(), in the entry of the error page.
 */
object ErrorPage {
    fun forWebProcessTermination(context: Context, url: String, reason: Int): String {
        val (title, description) = when (reason) {
            WebViewClient.WEB_PROCESS_EXCEEDED_MEMORY_LIMIT ->
                R.string.error_page_memory_title to R.string.error_page_memory_description
            WebViewClient.WEB_PROCESS_TERMINATED_BY_API ->
                R.string.error_page_stopped_title to R.string.error_page_stopped_description
            else -> R.string.error_page_crash_title to R.string.error_page_crash_description
        }
        return build(context.getString(title), context.getString(description), url,
                     context.getString(R.string.action_reload))
    }

    private fun build(title: String, description: String, url: String, reloadLabel: String): String {
        // A string literal of the script, without "<", so that it cannot end the script element.
        val urlLiteral = JSONObject.quote(url).replace("<", "\\u003c")
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="light dark">
            <title>${TextUtils.htmlEncode(title)}</title>
            <style>
              body { font-family: sans-serif; margin: 0; padding: 48px 24px; text-align: center; }
              h1 { font-size: 1.5em; margin: 0 0 16px; }
              p { color: GrayText; line-height: 1.5; margin: 0 0 8px; overflow-wrap: anywhere; }
              button { font: inherit; margin-top: 24px; padding: 12px 32px; border: none; border-radius: 24px;
                       background: #7542e5; color: white; }
            </style>
            <script>const pageUrl = $urlLiteral;</script>
            </head>
            <body>
            <h1>${TextUtils.htmlEncode(title)}</h1>
            <p>${TextUtils.htmlEncode(description)}</p>
            <p>${TextUtils.htmlEncode(url)}</p>
            <button onclick="location.replace(pageUrl)">${TextUtils.htmlEncode(reloadLabel)}</button>
            </body>
            </html>
        """.trimIndent()
    }
}
