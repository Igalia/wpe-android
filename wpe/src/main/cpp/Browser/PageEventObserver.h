/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Fernando Jimenez Moreno <fjimenez@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

#include <jni.h>
#include <wpe-webkit/wpe/webkit.h>

class PageEventObserver final {
public:
    PageEventObserver(JNIEnv* env, jclass klass, jobject obj);
    ~PageEventObserver();

    PageEventObserver(PageEventObserver&&) = delete;
    PageEventObserver& operator=(PageEventObserver&&) = delete;
    PageEventObserver(const PageEventObserver&) = delete;
    PageEventObserver& operator=(const PageEventObserver&) = delete;

    static constexpr int NB_JAVA_METHODS = 8;

    void onLoadChanged(WebKitLoadEvent loadEvent);
    void onLoadProgress(double progress);
    void onUriChanged(const char* uri);
    void onTitleChanged(const char* title, gboolean canGoBack, gboolean canGoForward);

    void onInputMethodContextIn();
    void onInputMethodContextOut();

    void enterFullscreenMode();
    void exitFullscreenMode();

private:
    template <typename... Args> void callJavaVoidMethod(int methodIdx, Args&&... args);

    jclass m_pageClass = nullptr;
    jobject m_pageObj = nullptr;
    jmethodID m_javaMethodIDs[NB_JAVA_METHODS] = {0};
};
