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

#include "JNI/JNI.h"
#include "JNIMappings.h"

#include <algorithm>
#include <libsoup/soup.h>
#include <vector>

DECLARE_JNI_CLASS_SIGNATURE(JNISoupTLD, "org/wpewebkit/wpe/SoupTLD");

namespace WebKit {

class JNISoupTLDCache final : public JNI::TypedClass<JNISoupTLD> {
public:
    JNISoupTLDCache()
        : JNI::TypedClass<JNISoupTLD>(true)
    {
        registerNativeMethods(
            JNI::StaticNativeMethod<jboolean(jstring)>("nativeDomainIsPublicSuffix", nativeDomainIsPublicSuffix));
    }

private:
    static jboolean nativeDomainIsPublicSuffix(JNIEnv* env, jclass, jstring domain)
    {
        const jsize length = env->GetStringLength(domain);
        if (!length)
            return JNI_FALSE;
        std::vector<gunichar2> units(static_cast<size_t>(length));
        env->GetStringRegion(domain, 0, length, reinterpret_cast<jchar*>(units.data()));
        // From here on the name is a C string, which ends at a NUL character and would be cut short. No domain has one.
        if (std::find(units.begin(), units.end(), 0) != units.end())
            return JNI_FALSE;
        g_autofree char* utf8 = g_utf16_to_utf8(units.data(), length, nullptr, nullptr, nullptr);
        if (!utf8)
            return JNI_FALSE;

        // The list holds names in lower case, and international names in their ASCII form too, which is the form that
        // g_hostname_to_ascii() gives, in lower case. The lookup ignores the final dot of a fully qualified name.
        g_autofree char* ascii = g_hostname_to_ascii(utf8);
        if (!ascii)
            return JNI_FALSE;
        return soup_tld_domain_is_public_suffix(ascii) ? JNI_TRUE : JNI_FALSE;
    }
};

static const JNISoupTLDCache& getJNISoupTLDCache()
{
    static const JNISoupTLDCache s_singleton;
    return s_singleton;
}

void configureSoupTLDJNIMappings()
{
    getJNISoupTLDCache();
}

} // namespace WebKit
