/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
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

#include "Browser.h"
#include "Page.h"
#include "PageSettings.h"
#include "WKCallback.h"
#include "WKCookieManager.h"
#include "WKNetworkSession.h"
#include "WKWebContext.h"
#include "WKWebsiteDataManager.h"

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* javaVM, void* /*reserved*/)
{
    try {
        JNI::initVM(javaVM);

        Browser::configureJNIMappings();
        WKCallback::configureJNIMappings();
        WKCookieManager::configureJNIMappings();
        WKNetworkSession::configureJNIMappings();
        WKWebContext::configureJNIMappings();
        WKWebsiteDataManager::configureJNIMappings();
        Page::configureJNIMappings();
        PageSettings::configureJNIMappings();

        return JNI::VERSION;
    } catch (...) {
        return JNI_ERR;
    }
}
