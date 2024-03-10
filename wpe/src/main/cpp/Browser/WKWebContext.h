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

#pragma once

#include "JNI/JNI.h"

#include <wpe/webkit.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWKWebContext, "com/wpe/wpe/WKWebContext");

class WKWebsiteDataManager;

class WKWebContext final {
public:
    static void configureJNIMappings();

    WKWebContext(WKWebContext&&) = delete;
    WKWebContext& operator=(WKWebContext&&) = delete;
    WKWebContext(const WKWebContext&) = delete;
    WKWebContext& operator=(const WKWebContext&) = delete;

    ~WKWebContext() = default;

    WebKitWebContext* webContext() const noexcept { return m_webContext.get(); }

    bool automationMode() const { return m_automationMode; }

private:
    friend class JNIWKWebContextCache;

    WKWebContext(
        JNIEnv* env, JNIWKWebContext jniWKWebContext, WKWebsiteDataManager* wkWebsiteDataManager, bool automationMode);

    template <typename T> using ProtectedUniquePointer = std::unique_ptr<T, std::function<void(T*)>>;

    JNI::ProtectedType<JNIWKWebContext> m_webContextJavaInstance;

    bool m_automationMode = false;

    ProtectedUniquePointer<WebKitWebContext> m_webContext {};
};
