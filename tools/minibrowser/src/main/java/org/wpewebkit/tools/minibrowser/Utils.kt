package org.wpewebkit.tools.minibrowser

import android.content.Intent
import android.util.Patterns
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

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

// A scheme, as in "https:" or "about:". A host and its port, as in "example.org:8080", look like
// one, but the port starts with a digit.
private val scheme = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:(?![0-9])""")

// Uses an address that starts with a scheme as it is, and adds http:// to the others. Characters
// that URI() refuses, such as [ ], do not matter: WebKit encodes them.
fun normalizeAddress(address: String): String {
    return if (scheme.containsMatchIn(address)) address else "http://$address"
}

// Returns the address another app asked to open, or null if the intent carries none.
fun Intent.addressToOpen(): String? {
    if (action != Intent.ACTION_SEND)
        return dataString
    // Apps may share styled text, which is not a String.
    val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return null
    return findLink(text)
}

// Quotes and punctuation of other scripts that close a quote or a sentence. They are not part of
// addresses, and may follow a link without a space between them.
private const val CLOSING_PUNCTUATION = "”’»›」』】）。、，．！？；："

private val linkWithScheme = Regex("""\bhttps?://[^\s<>"$CLOSING_PUNCTUATION]+""", RegexOption.IGNORE_CASE)

private val BRACKETS = mapOf(')' to '(', ']' to '[', '}' to '{')

// A shared text may hold other words around the link. A link with a scheme is preferred, as
// words like file names, such as README.md, also look like addresses. Patterns.WEB_URL is not
// used for it, as it drops a last / or ) from a link.
private fun findLink(text: String): String? {
    val match = linkWithScheme.find(text) ?: Patterns.WEB_URL.toRegex().find(text) ?: return null
    return trimTrailingPunctuation(match.value)
}

// Drops the punctuation that ends the sentence or closes the quote or the bracket around a link,
// which is matched as part of it. A closing bracket is kept when it has an opening one in the link.
private fun trimTrailingPunctuation(link: String): String {
    var end = link.length
    while (end > 0) {
        val c = link[end - 1]
        val opening = BRACKETS[c]
        val unbalanced = opening != null &&
            link.subSequence(0, end).count { it == c } > link.subSequence(0, end).count { it == opening }
        if (c !in ".,;:!?'\"" && c !in CLOSING_PUNCTUATION && !unbalanced)
            break
        end--
    }
    return link.substring(0, end)
}
