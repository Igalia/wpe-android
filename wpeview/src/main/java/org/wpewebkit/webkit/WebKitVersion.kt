@file:JvmName("WebKitVersion")

package org.wpewebkit.webkit

import org.wpewebkit.ffm.*

public val major: Int
    get() = webkit_get_major_version() as Int

public val minor: Int
    get() = webkit_get_minor_version() as Int

public val micro: Int
    get() = webkit_get_micro_version() as Int

public val string: String
    get() = "${major}.${minor}.${micro}"
