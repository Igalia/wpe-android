/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Jani Hautakangas <jani@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

#include "InputMethodContext.h"
#include "JNI/JNI.h"
#include "Renderer.h"

#include <vector>

DECLARE_JNI_CLASS_SIGNATURE(JNIPage, "com/wpe/wpe/Page");

struct WPEAndroidViewBackend;

class Page final : public InputMethodContextObserver {
public:
    static void configureJNIMappings();

    Page(Page&&) = delete;
    Page& operator=(Page&&) = delete;
    Page(const Page&) = delete;
    Page& operator=(const Page&) = delete;

    ~Page() override { close(); }

    void close() noexcept;

    WebKitWebView* webView() const noexcept { return m_webView; }

    void onInputMethodContextIn() noexcept override;
    void onInputMethodContextOut() noexcept override;

    void commitBuffer(WPEAndroidBuffer* buffer, int fenceFD) noexcept;

private:
    friend class JNIPageCache;

    Page(JNIEnv* env, JNIPage jniPage, int width, int height);

    JNI::ProtectedType<JNIPage> m_pageJavaInstance;
    InputMethodContext m_inputMethodContext;
    std::shared_ptr<Renderer> m_renderer;

    WPEAndroidViewBackend* m_viewBackend = nullptr;
    WebKitWebView* m_webView = nullptr;
    std::vector<gulong> m_signalHandlers;
    bool m_isFullscreenRequested = false;
};
