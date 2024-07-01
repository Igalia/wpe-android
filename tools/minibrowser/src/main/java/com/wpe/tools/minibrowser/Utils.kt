package com.wpe.tools.minibrowse

import java.net.URI

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
