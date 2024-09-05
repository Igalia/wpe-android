/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Zan Dobersek <zdobersek@igalia.com>
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

#include "LooperThread.h"

#include "Logging.h"

LooperThread::~LooperThread()
{
    if (m_looper != nullptr)
        ALooper_release(m_looper);
}

void LooperThread::startLooper() noexcept
{
    if (m_looper == nullptr) {
        m_looper = ALooper_forThread();
        Logging::logDebug("LooperThread::startLooper() with looper: %p", m_looper);
        ALooper_acquire(m_looper);
    }
}
