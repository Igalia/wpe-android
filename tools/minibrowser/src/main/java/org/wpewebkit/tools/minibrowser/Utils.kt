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

object Utils {
    private fun addressHasWebScheme(address: String) : Boolean {
        val uri = URI(address)
        return uri.scheme?.let {
            it == "http"
        } ?: false
    }

    fun normalizeAddress(address: String) : String {
        return if (!addressHasWebScheme(address)) {
            return "http://$address"
        } else {
            address
        }
    }
}
