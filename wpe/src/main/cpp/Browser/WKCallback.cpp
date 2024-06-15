/**
 * Copyright (C) 2024 Igalia S.L. <info@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
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

#include "WKCallback.h"

#include "Logging.h"

/***********************************************************************************************************************
 * JNI mapping with Java WKCallback class
 **********************************************************************************************************************/

class JNIWKCallbackCache final : public JNI::TypedClass<JNIWKCallback> {
public:
    JNIWKCallbackCache()
        : JNI::TypedClass<JNIWKCallback>(true)
        , m_onStringResult(getStaticMethod<void(JNIWKCallback, jstring)>("onStringResult"))
    {
    }

    void onStringResult(JNIWKCallback callback, jstring result) const noexcept
    {
        try {
            m_onStringResult.invoke(callback, result);
        } catch (const std::exception& ex) {
            Logging::logError("cannot call WKCallback callback result (%s)", ex.what());
        }
        /*
                try {
                    JNIEnv* env = JNI::getCurrentThreadJNIEnv();
                    //env->DeleteGlobalRef(callback);
                } catch (const std::exception& ex) {
                    Logging::logError("Failed to release WKCallback reference (%s)", ex.what());
                }
        */
    }
    /*
        void onResult(JNIWKWebsiteDataManagerCallbackHolder callbackHolder, jboolean result) const noexcept
        {
            try {
                m_commitResult.invoke(callbackHolder, result);
            } catch (const std::exception& ex) {
                Logging::logError("cannot call WKWebsiteDataManager callback result (%s)", ex.what());
            }

            try {
                JNIEnv* env = JNI::getCurrentThreadJNIEnv();
                env->DeleteGlobalRef(callbackHolder);
            } catch (const std::exception& ex) {
                Logging::logError("Failed to release WKWebsiteDataManager.CallbackHolder reference (%s)", ex.what());
            }
        }
    */
private:
    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::StaticMethod<void(JNIWKCallback, jstring)> m_onStringResult;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
};

const JNIWKCallbackCache& getJNIWKCallbackCache()
{
    static const JNIWKCallbackCache s_singleton;
    return s_singleton;
}

namespace WKCallback {

void configureJNIMappings() { getJNIWKCallbackCache(); }

void onStringResult(JNIWKCallback callback, jstring result)
{
    getJNIWKCallbackCache().onStringResult(callback, result);
}

} // namespace WKCallback
