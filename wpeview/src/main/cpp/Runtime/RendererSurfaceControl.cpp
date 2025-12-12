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

// Initialize the surface control when the Android surface is created
void RendererSurfaceControl::onSurfaceCreated(ANativeWindow* window) noexcept
{
    m_surface = std::make_shared<SurfaceControl::Surface>(window, "Surface");

    // Clear any existing queued transactions for the old surface.
    m_numTransactionCommitOrAckPending = 0;
    while (!m_pendingTransactionQueue.empty()) {
        m_pendingTransactionQueue.pop();
    }
}

// Update surface dimensions when the Android surface changes
void RendererSurfaceControl::onSurfaceChanged(int /*format*/, uint32_t width, uint32_t height) noexcept
{
    m_size.m_width = width;
    m_size.m_height = height;
}

// Handle Android surface redraw requests by committing pending buffers or refreshing current content
void RendererSurfaceControl::onSurfaceRedrawNeeded() noexcept // NOLINT(bugprone-exception-escape)
{
    Logging::logDebug("onSurfaceRedrawNeeded()");

    if (m_surface == nullptr) {
        Logging::logDebug("No surface available");
        return;
    }

    // Commit pending buffer if it exists (deferred from when surface was unavailable)
    if (m_pendingCommitBuffer != nullptr) {
        Logging::logDebug("Committing pending buffer");

        AHardwareBuffer* hardwareBuffer = wpe_buffer_android_get_hardware_buffer(m_pendingCommitBuffer);
        if (hardwareBuffer == nullptr) {
            Logging::logError("Failed to get hardware buffer from pending buffer");
            return;
        }

        // Transfer ownership: save buffer/fence, clear pending state, apply transaction, release local ref
        WPEBufferAndroid* bufferToCommit = m_pendingCommitBuffer;
        int fenceFD = m_pendingCommitFenceFD ? m_pendingCommitFenceFD->release() : -1;
        m_pendingCommitBuffer = nullptr;
        m_pendingCommitFenceFD.reset();
        applyBufferTransaction(hardwareBuffer, bufferToCommit, fenceFD);
        g_object_unref(bufferToCommit);

        return;
    }

    // Refresh display with front buffer if available and no newer content is pending
    if (m_frontBuffer != nullptr) {
        Logging::logDebug("Redrawing front buffer");

        if (m_numTransactionCommitOrAckPending > 0) {
            Logging::logDebug("Skipping redraw, newer content pending");
            return;
        }

        AHardwareBuffer* hardwareBuffer = wpe_buffer_android_get_hardware_buffer(m_frontBuffer);
        if (hardwareBuffer == nullptr) {
            Logging::logError("Failed to get hardware buffer from front buffer");
            return;
        }

        // Apply transaction with commit callback but no complete callback (refresh only, not a new frame)
        SurfaceControl::Transaction transaction;
        transaction.setVisibility(*m_surface, kSurfaceVisibility);
        transaction.setZOrder(*m_surface, kSurfaceZOrder);
        transaction.setBuffer(*m_surface, hardwareBuffer, -1);

        std::weak_ptr<RendererSurfaceControl> const weakPtr(shared_from_this());
        auto onCommitCallback = [weakPtr]() {
            auto ptr = weakPtr.lock();
            if (ptr)
                ptr->onTransactionCommittedOnBrowserThread();
        };
        transaction.setOnCommitCallback(std::move(onCommitCallback));

        m_numTransactionCommitOrAckPending++;
        transaction.apply();

        return;
    }

    Logging::logDebug("No buffer available");
}

// Release the surface control when the Android surface is destroyed
void RendererSurfaceControl::onSurfaceDestroyed() noexcept { m_surface.reset(); }

// Commit a new buffer to the surface or defer if surface is unavailable
void RendererSurfaceControl::commitBuffer(
    AHardwareBuffer* hardwareBuffer, WPEBufferAndroid* wpeBuffer, std::shared_ptr<ScopedFD> fenceFD)
{
    // Defer commit until surface is restored
    if (m_surface == nullptr) {
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

    if (m_pendingCommitBuffer != nullptr) {
        if (m_bufferReleaseCallback)
            m_bufferReleaseCallback(m_pendingCommitBuffer);
        g_object_unref(m_pendingCommitBuffer);
        m_pendingCommitBuffer = nullptr;
        m_pendingCommitFenceFD = nullptr;
    }

    applyBufferTransaction(hardwareBuffer, wpeBuffer, fenceFD->release());
}

// Handle transaction completion acknowledgment and release buffer for reuse
void RendererSurfaceControl::onTransActionAckOnBrowserThread(
    std::optional<WPEBufferAndroid*> releasedBuffer, SurfaceControl::TransactionStats stats)
{
    // Android framework releases its buffer reference in this callback; wait for it before buffer reuse
    if (releasedBuffer.has_value() && releasedBuffer.value() != nullptr) {
        auto* buffer = releasedBuffer.value();

        // Set release fence on buffer so Web Process waits before reusing
        if (!stats.m_surfaceStats.empty() && stats.m_surfaceStats[0].m_fence) {
            int releaseFence = stats.m_surfaceStats[0].m_fence->release();
            wpe_buffer_set_release_fence(WPE_BUFFER(buffer), releaseFence);
        }

        if (m_frontBuffer && m_frontBuffer == buffer) {
            g_object_unref(m_frontBuffer);
            m_frontBuffer = nullptr;
        }

        if (m_bufferReleaseCallback)
            m_bufferReleaseCallback(buffer);
        g_object_unref(buffer);
    }
}

// Process transaction commit completion, trigger frame callback, and apply queued transactions
void RendererSurfaceControl::onTransactionCommittedOnBrowserThread()
{
    if (m_frameCompleteCallback)
        m_frameCompleteCallback();

    if (m_numTransactionCommitOrAckPending > 0) {
        m_numTransactionCommitOrAckPending--;
    }

    if (!m_pendingTransactionQueue.empty()) {
        m_numTransactionCommitOrAckPending++;
        m_pendingTransactionQueue.front().apply();
        m_pendingTransactionQueue.pop();
    }
}

// Create and apply buffer transaction with callbacks, queueing if another transaction is pending
void RendererSurfaceControl::applyBufferTransaction(
    AHardwareBuffer* hardwareBuffer, WPEBufferAndroid* wpeBuffer, int fenceFD)
{
    SurfaceControl::Transaction transaction;
    transaction.setVisibility(*m_surface, kSurfaceVisibility);
    transaction.setZOrder(*m_surface, kSurfaceZOrder);
    transaction.setBuffer(*m_surface, hardwareBuffer, fenceFD);

    std::optional<WPEBufferAndroid*> bufferToRelease = m_currentFrameBuffer;
    m_currentFrameBuffer = wpeBuffer;
    g_object_ref(wpeBuffer);

    std::weak_ptr<RendererSurfaceControl> const weakPtr(shared_from_this());
    auto onCompleteCallback = [weakPtr, buffer = bufferToRelease](SurfaceControl::TransactionStats&& stats) {
        auto ptr = weakPtr.lock();
        if (ptr)
            ptr->onTransActionAckOnBrowserThread(buffer, std::move(stats));
    };
    transaction.setOnCompleteCallback(std::move(onCompleteCallback));

    auto onCommitCallback = [weakPtr]() {
        auto ptr = weakPtr.lock();
        if (ptr)
            ptr->onTransactionCommittedOnBrowserThread();
    };
    transaction.setOnCommitCallback(std::move(onCommitCallback));

    // Queue transaction if one is already pending; otherwise apply immediately and update front buffer
    if (m_numTransactionCommitOrAckPending > 0) {
        m_pendingTransactionQueue.push(std::move(transaction));
    } else {
        m_numTransactionCommitOrAckPending++;
        transaction.apply();
        m_frontBuffer = wpeBuffer;
        g_object_ref(wpeBuffer);
    }
}
