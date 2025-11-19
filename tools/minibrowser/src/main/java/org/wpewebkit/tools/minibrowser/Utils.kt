package org.wpewebkit.tools.minibrowser

import android.view.View
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

// Ensures that the address has an HTTP/HTTPS scheme, adding HTTPS if missing
fun normalizeAddress(address: String): String {
    // Returns true if a scheme exists and is "http" or "https" (case-insensitive)
    val hasWebScheme = try {
        val uri = URI(address)
        uri.scheme?.lowercase() in listOf("http", "https")
    } catch (_: Exception) {
        false
    }

    if (hasWebScheme) {
        return address
    }
    return "https://$address"
}
