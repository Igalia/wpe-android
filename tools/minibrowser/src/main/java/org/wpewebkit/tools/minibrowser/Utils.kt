package org.wpewebkit.tools.minibrowser

import android.view.View
import android.webkit.URLUtil
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.net.URI

fun View.requestApplyInsetsWhenAttached() {
    if (isAttachedToWindow) {
        requestApplyInsets()
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                v.requestApplyInsets()
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }
}

fun View.requestApplyStandardInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val padding = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
            androidx.core.graphics.Insets.max(systemBars, insets.getInsets(WindowInsetsCompat.Type.ime()))
        } else {
            systemBars
        }
        view.updatePadding(padding.left, padding.top, padding.right, padding.bottom)
        WindowInsetsCompat.CONSUMED
    }
    requestApplyInsetsWhenAttached()
}

object Utils {
    private fun addressHasWebScheme(address: String) : Boolean {
        return try {
            val uri = URI(address)
            uri.scheme?.let {
                it.equals("http", ignoreCase = true) || it.equals("https", ignoreCase = true)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun normalizeAddress(address: String) : String {
        // If the address already has a valid HTTP/HTTPS scheme, return as-is
        if (addressHasWebScheme(address)) {
            return address
        }

        // Otherwise, add HTTPS scheme
        val urlWithScheme = "https://$address"

        // Validate the constructed URL using Android's URLUtil
        return if (URLUtil.isValidUrl(urlWithScheme) &&
                   (URLUtil.isHttpUrl(urlWithScheme) || URLUtil.isHttpsUrl(urlWithScheme))) {
            urlWithScheme
        } else {
            // Fallback: return the original address with HTTPS prepended
            // This allows the WebView to handle any errors
            urlWithScheme
        }
    }
}
