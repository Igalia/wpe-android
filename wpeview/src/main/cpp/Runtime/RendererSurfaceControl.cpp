/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
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

#include "RendererSurfaceControl.h"

#include <utility>

#include "Logging.h"
#include "SurfaceControl.h"

#include <wpe/wpe-platform.h>

namespace {
// Surface transaction constants
// Visibility is always SHOW for active rendering surfaces
constexpr ASurfaceTransactionVisibility kSurfaceVisibility = ASURFACE_TRANSACTION_VISIBILITY_SHOW;
// Z-order determines stacking; 0 is the base layer
constexpr int32_t kSurfaceZOrder = 0;
} // namespace

RendererSurfaceControl::RendererSurfaceControl(uint32_t width, uint32_t height)
    : m_size({width, height})
{
    Logging::logDebug("RendererSurfaceControl(%u, %u)", m_size.m_width, m_size.m_height);
}

RendererSurfaceControl::~RendererSurfaceControl()
{
    Logging::logDebug("~RendererSurfaceControl()");
    while (!m_pendingTransactionQueue.empty()) {
        m_pendingTransactionQueue.front().setParent(*m_surface, nullptr);
        m_pendingTransactionQueue.front().apply();
        m_pendingTransactionQueue.pop();
        m_numTransactionCommitOrAckPending--;
    }

    if (m_frontBuffer) {
        g_object_unref(m_frontBuffer);
        m_frontBuffer = nullptr;
    }

    if (m_pendingCommitBuffer) {
        g_object_unref(m_pendingCommitBuffer);
        m_pendingCommitBuffer = nullptr;
    }
}

void RendererSurfaceControl::onSurfaceCreated(ANativeWindow* window) noexcept
{
    m_surface = std::make_shared<SurfaceControl::Surface>(window, "Surface");
}

void RendererSurfaceControl::onSurfaceChanged(int /*format*/, uint32_t width, uint32_t height) noexcept
{
    m_size.m_width = width;
    m_size.m_height = height;
}

void RendererSurfaceControl::onSurfaceRedrawNeeded() noexcept // NOLINT(bugprone-exception-escape)
{
    Logging::logDebug("RendererSurfaceControl::onSurfaceRedrawNeeded()");

    // Must have a surface to draw to
    if (m_surface == nullptr) {
        Logging::logDebug("RendererSurfaceControl::onSurfaceRedrawNeeded() - no surface available");
        return;
    }

    // Case 1: Pending commit buffer exists (deferred commit after surface restoration)
    if (m_pendingCommitBuffer != nullptr) {
        Logging::logDebug("RendererSurfaceControl::onSurfaceRedrawNeeded() - committing pending buffer");

        // Extract hardware buffer from pending buffer
        AHardwareBuffer* hardwareBuffer = wpe_buffer_android_get_hardware_buffer(m_pendingCommitBuffer);
        if (hardwareBuffer == nullptr) {
            Logging::logError("RendererSurfaceControl::onSurfaceRedrawNeeded() - failed to get hardware buffer from "
                              "pending buffer");
            return;
        }

        // Save buffer pointer and fence before clearing pending state
        WPEBufferAndroid* bufferToCommit = m_pendingCommitBuffer;
        int fenceFD = m_pendingCommitFenceFD ? m_pendingCommitFenceFD->release() : -1;

        // Clear pending state
        m_pendingCommitBuffer = nullptr;
        m_pendingCommitFenceFD.reset();

        // Apply the buffer transaction using shared logic
        applyBufferTransaction(hardwareBuffer, bufferToCommit, fenceFD);

        // Release the original pending buffer ref (now held by currentFrameResources and possibly frontBuffer)
        g_object_unref(bufferToCommit);

        return;
    }

    // Case 2: Front buffer redraw (refresh)
    if (m_frontBuffer != nullptr) {
        Logging::logDebug("RendererSurfaceControl::onSurfaceRedrawNeeded() - redrawing front buffer");

        // Skip redraw if there are pending transactions or queued updates
        // The pending/queued buffers are newer than m_frontBuffer
        if (m_numTransactionCommitOrAckPending > 0) {
            Logging::logDebug(
                "RendererSurfaceControl::onSurfaceRedrawNeeded() - skipping redraw, newer content pending");
            return;
        }

        // Extract hardware buffer from front buffer
        AHardwareBuffer* hardwareBuffer = wpe_buffer_android_get_hardware_buffer(m_frontBuffer);
        if (hardwareBuffer == nullptr) {
            Logging::logError(
                "RendererSurfaceControl::onSurfaceRedrawNeeded() - failed to get hardware buffer from front buffer");
            return;
        }

        // Create transaction
        SurfaceControl::Transaction transaction;
        transaction.setVisibility(*m_surface, kSurfaceVisibility);
        transaction.setZOrder(*m_surface, kSurfaceZOrder);
        transaction.setBuffer(*m_surface, hardwareBuffer, -1); // No fence needed (buffer already signaled)

        // Setup commit callback for queue management (no complete callback - this is a refresh, not a new frame)
        std::weak_ptr<RendererSurfaceControl> const weakPtr(shared_from_this());
        auto onCommitCallback = [weakPtr]() {
            auto ptr = weakPtr.lock();
            if (ptr)
                ptr->onTransactionCommittedOnBrowserThread();
        };
        transaction.setOnCommitCallback(std::move(onCommitCallback));

        // Apply immediately (counter is guaranteed to be 0 here due to early return above)
        m_numTransactionCommitOrAckPending++;
        transaction.apply();

        return;
    }

    // Case 3: No buffer available
    Logging::logDebug("RendererSurfaceControl::onSurfaceRedrawNeeded() - no buffer available to redraw");
}

void RendererSurfaceControl::onSurfaceDestroyed() noexcept { m_surface.reset(); }

void RendererSurfaceControl::commitBuffer(
    AHardwareBuffer* hardwareBuffer, WPEBufferAndroid* wpeBuffer, std::shared_ptr<ScopedFD> fenceFD)
{
    if (m_surface == nullptr) { // surface is lost
        if (m_pendingCommitBuffer != nullptr && m_bufferReleaseCallback) {
            m_bufferReleaseCallback(m_pendingCommitBuffer);
            g_object_unref(m_pendingCommitBuffer);
            m_pendingCommitBuffer = nullptr;
        }

        m_pendingCommitBuffer = wpeBuffer;
        g_object_ref(wpeBuffer);
        m_pendingCommitFenceFD = fenceFD;
        if (m_frontBuffer != nullptr && m_bufferReleaseCallback) {
            m_bufferReleaseCallback(m_frontBuffer);
            g_object_unref(m_frontBuffer);
            m_frontBuffer = nullptr;
        }
        return;
    }

    // Clear any pending buffer before committing new one
    if (m_pendingCommitBuffer != nullptr) {
        if (m_bufferReleaseCallback)
            m_bufferReleaseCallback(m_pendingCommitBuffer);
        g_object_unref(m_pendingCommitBuffer);
        m_pendingCommitBuffer = nullptr;
        m_pendingCommitFenceFD = nullptr;
    }

    applyBufferTransaction(hardwareBuffer, wpeBuffer, fenceFD->release());
}

void RendererSurfaceControl::onTransActionAckOnBrowserThread(std::optional<WPEBufferAndroid*> releasedBuffer)
{
    // Android framework releases its ref on the buffer in this OnComplete callback.
    // We must wait for this callback before reusing the buffer to avoid synchronization errors.
    if (releasedBuffer.has_value() && releasedBuffer.value() != nullptr) {
        auto* buffer = releasedBuffer.value();

        if (m_frontBuffer && m_frontBuffer == buffer) {
            g_object_unref(m_frontBuffer);
            m_frontBuffer = nullptr;
        }

        if (m_bufferReleaseCallback)
            m_bufferReleaseCallback(buffer);
        g_object_unref(buffer);
    }
}

void RendererSurfaceControl::onTransactionCommittedOnBrowserThread()
{
    if (m_frameCompleteCallback)
        m_frameCompleteCallback();

    // Process pending transaction queue
    m_numTransactionCommitOrAckPending--;
    if (!m_pendingTransactionQueue.empty()) {
        m_numTransactionCommitOrAckPending++;
        m_pendingTransactionQueue.front().apply();
        m_pendingTransactionQueue.pop();
    }
}

void RendererSurfaceControl::applyBufferTransaction(
    AHardwareBuffer* hardwareBuffer, WPEBufferAndroid* wpeBuffer, int fenceFD)
{
    // Create and configure transaction
    SurfaceControl::Transaction transaction;
    transaction.setVisibility(*m_surface, kSurfaceVisibility);
    transaction.setZOrder(*m_surface, kSurfaceZOrder);
    transaction.setBuffer(*m_surface, hardwareBuffer, fenceFD);

    // Swap current buffer for release and set new buffer
    std::optional<WPEBufferAndroid*> bufferToRelease = m_currentFrameBuffer;
    m_currentFrameBuffer = wpeBuffer;
    g_object_ref(wpeBuffer);

    // Register transaction callbacks
    std::weak_ptr<RendererSurfaceControl> const weakPtr(shared_from_this());
    auto onCompleteCallback = [weakPtr, buffer = bufferToRelease](auto&& /*stats*/) {
        auto ptr = weakPtr.lock();
        if (ptr)
            ptr->onTransActionAckOnBrowserThread(buffer);
    };
    transaction.setOnCompleteCallback(std::move(onCompleteCallback));

    auto onCommitCallback = [weakPtr]() {
        auto ptr = weakPtr.lock();
        if (ptr)
            ptr->onTransactionCommittedOnBrowserThread();
    };
    transaction.setOnCommitCallback(std::move(onCommitCallback));

    // Apply or queue the transaction
    if (m_numTransactionCommitOrAckPending > 0) {
        m_pendingTransactionQueue.push(std::move(transaction));
    } else {
        m_numTransactionCommitOrAckPending++;
        transaction.apply();
        m_frontBuffer = wpeBuffer;
        g_object_ref(wpeBuffer);
    }
}
