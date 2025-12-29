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

DECLARE_JNI_CLASS_SIGNATURE(JNIWKWebView, "org/wpewebkit/wpe/WKWebView");

using WPEDisplay = struct _WPEDisplay;
using WPEViewAndroid = struct _WPEViewAndroid;
class WKWebContext;
class RendererSurfaceControl;

class WKWebView final : public InputMethodContextObserver {
public:
    static void configureJNIMappings();

    WKWebView(WKWebView&&) = delete;
    WKWebView& operator=(WKWebView&&) = delete;
    WKWebView(const WKWebView&) = delete;
    WKWebView& operator=(const WKWebView&) = delete;

    ~WKWebView() override { close(); }

    void close() noexcept;

    float deviceScale() const noexcept { return m_deviceScale; }
    WebKitWebView* webView() const noexcept { return m_webView; }
    WPEViewAndroid* wpeView() const noexcept { return m_wpeView; }

    void onInputMethodContextIn() noexcept override;
    void onInputMethodContextOut() noexcept override;

private:
    friend class JNIWKWebViewCache;

    WKWebView(JNIEnv* env, JNIWKWebView jniWKWebView, WKWebContext* wkWebContext, int width, int height,
        float deviceScale, bool headless);

    JNI::ProtectedType<JNIWKWebView> m_webViewJavaInstance;
    InputMethodContext m_inputMethodContext;
    std::shared_ptr<RendererSurfaceControl> m_renderer;

    WPEDisplay* m_wpeDisplay = nullptr;
    WPEViewAndroid* m_wpeView = nullptr;
    WebKitWebView* m_webView = nullptr;
    std::vector<gulong> m_signalHandlers;
    bool m_isFullscreenRequested = false;
    bool m_isHeadless = false;
    float m_deviceScale;
};
