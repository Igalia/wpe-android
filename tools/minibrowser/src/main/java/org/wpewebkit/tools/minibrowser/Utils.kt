package org.wpewebkit.tools.minibrowser

import android.content.Intent
import android.util.Patterns
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

// If a string may be parsed as an URI that has an scheme, use it as-is.
// Otherwise fall-back to adding http://.
fun normalizeAddress(address: String): String {
    val hasScheme = try {
        val uri = URI(address)
        uri.scheme != null
    } catch (_: Exception) {
        false
    }
    return if (hasScheme) address else "http://$address"
}

// Returns the address another app asked to open, or null if the intent carries none.
fun Intent.addressToOpen(): String? {
    if (action != Intent.ACTION_SEND)
        return dataString
    // Apps may share styled text, which is not a String.
    val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return null
    return findLink(text)
}

private val linkWithScheme = Regex("""\bhttps?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

// A shared text may hold other words around the link. A link with a scheme is preferred, as
// words like file names, such as README.md, also look like addresses. Patterns.WEB_URL is not
// used for it, as it drops a last / or ) from a link.
private fun findLink(text: String): String? {
    val match = linkWithScheme.find(text) ?: Patterns.WEB_URL.toRegex().find(text) ?: return null
    return trimTrailingPunctuation(match.value)
}

// Drops the punctuation that ends the sentence or closes the parenthesis around a link, which
// is matched as part of it. A closing parenthesis is kept when it has an opening one in the link.
private fun trimTrailingPunctuation(link: String): String {
    var end = link.length
    while (end > 0) {
        val c = link[end - 1]
        val unbalanced = c == ')' &&
            link.subSequence(0, end).count { it == ')' } > link.subSequence(0, end).count { it == '(' }
        if (c !in ".,;:!?'\"" && !unbalanced)
            break
        end--
    }
    return link.substring(0, end)
}
