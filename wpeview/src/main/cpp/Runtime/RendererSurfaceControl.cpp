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
        if (m_surface) {
            m_pendingTransactionQueue.front().setParent(*m_surface, nullptr);
            m_pendingTransactionQueue.front().apply();
        }
        m_pendingTransactionQueue.pop();
    }

    if (m_currentFrameBuffer != nullptr) {
        g_object_unref(m_currentFrameBuffer);
        m_currentFrameBuffer = nullptr;
    }

    if (m_pendingCommitBuffer != nullptr) {
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
    Logging::logDebug(
        "onSurfaceChanged(%u, %u) - previous size (%u, %u)", width, height, m_size.m_width, m_size.m_height);

    bool const sizeChanged = (m_size.m_width != width || m_size.m_height != height);
    m_size.m_width = width;
    m_size.m_height = height;

    if (!sizeChanged)
        return;

    // Size changed - invalidate all stale buffers that have old dimensions

    // Release pending commit buffer (old dimensions)
    if (m_pendingCommitBuffer != nullptr) {
        if (m_visibleWPEView != nullptr)
            wpe_view_buffer_released(m_visibleWPEView, WPE_BUFFER(m_pendingCommitBuffer));
        g_object_unref(m_pendingCommitBuffer);
        m_pendingCommitBuffer = nullptr;
        m_pendingCommitFenceFD.reset();
    }

    // Release current frame buffer (in-flight with old dimensions)
    if (m_currentFrameBuffer != nullptr) {
        if (m_visibleWPEView != nullptr)
            wpe_view_buffer_released(m_visibleWPEView, WPE_BUFFER(m_currentFrameBuffer));
        g_object_unref(m_currentFrameBuffer);
        m_currentFrameBuffer = nullptr;
    }

    // Clear pending transaction queue (old-sized buffers)
    while (!m_pendingTransactionQueue.empty())
        m_pendingTransactionQueue.pop();
    m_numTransactionCommitOrAckPending = 0;

    Logging::logDebug("onSurfaceChanged: cleared stale buffers for resize");
}

// Handle Android surface redraw requests by committing pending buffers
void RendererSurfaceControl::onSurfaceRedrawNeeded() noexcept
{
    Logging::logDebug("onSurfaceRedrawNeeded()");

    if (m_surface == nullptr) {
        Logging::logDebug("No surface available");
        return;
    }

    // Only handle deferred commits from when surface was unavailable
    if (m_pendingCommitBuffer != nullptr) {
        Logging::logDebug("Committing pending buffer");

        AHardwareBuffer* hardwareBuffer = wpe_buffer_android_get_hardware_buffer(m_pendingCommitBuffer);
        if (hardwareBuffer == nullptr) {
            Logging::logError("Failed to get hardware buffer from pending buffer");
            return;
        }

        // Transfer ownership and apply transaction
        WPEBufferAndroid* bufferToCommit = m_pendingCommitBuffer;
        int fenceFD = m_pendingCommitFenceFD ? m_pendingCommitFenceFD->release() : -1;
        m_pendingCommitBuffer = nullptr;
        m_pendingCommitFenceFD.reset();

        applyBufferTransaction(hardwareBuffer, bufferToCommit, fenceFD);
        g_object_unref(bufferToCommit);
        return;
    }

    // No pending buffer - WebKit will render new content when ready
    Logging::logDebug("No pending buffer - waiting for WebKit");
}

// Release the surface control when the Android surface is destroyed
void RendererSurfaceControl::onSurfaceDestroyed() noexcept
{
    Logging::logDebug("onSurfaceDestroyed()");
    m_surface.reset();
}

// Commit a new buffer to the surface or defer if surface is unavailable
void RendererSurfaceControl::commitBuffer(
    AHardwareBuffer* hardwareBuffer, WPEBufferAndroid* wpeBuffer, std::shared_ptr<ScopedFD> fenceFD)
{
    // Defer commit until surface is restored
    if (m_surface == nullptr) {
        // Release old pending commit buffer if exists
        if (m_pendingCommitBuffer != nullptr) {
            if (m_visibleWPEView != nullptr)
                wpe_view_buffer_released(m_visibleWPEView, WPE_BUFFER(m_pendingCommitBuffer));
            g_object_unref(m_pendingCommitBuffer);
        }

        // Store new buffer as pending
        m_pendingCommitBuffer = wpeBuffer;
        g_object_ref(m_pendingCommitBuffer);
        m_pendingCommitFenceFD = fenceFD;

        // Release current frame buffer since we can't display anything
        if (m_currentFrameBuffer != nullptr) {
            if (m_visibleWPEView != nullptr)
                wpe_view_buffer_released(m_visibleWPEView, WPE_BUFFER(m_currentFrameBuffer));
            g_object_unref(m_currentFrameBuffer);
            m_currentFrameBuffer = nullptr;
        }
        return;
    }

    // Surface available - release any pending buffer from when surface was unavailable
    if (m_pendingCommitBuffer != nullptr) {
        if (m_visibleWPEView != nullptr)
            wpe_view_buffer_released(m_visibleWPEView, WPE_BUFFER(m_pendingCommitBuffer));
        g_object_unref(m_pendingCommitBuffer);
        m_pendingCommitBuffer = nullptr;
        m_pendingCommitFenceFD.reset();
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

        // Notify WebKit that the buffer can be reused
        if (m_visibleWPEView != nullptr) {
            Logging::logDebug("onTransActionAckOnBrowserThread: buffer_released %p", buffer);
            wpe_view_buffer_released(m_visibleWPEView, WPE_BUFFER(buffer));
        }
        // Note: Callback handles g_object_unref for the captured buffer
    }
}

// Process transaction commit completion, trigger buffer rendered callback, and apply queued transactions
void RendererSurfaceControl::onTransactionCommittedOnBrowserThread(std::optional<WPEBufferAndroid*> renderedBuffer)
{
    // Notify WebKit that the buffer is now on screen
    if (renderedBuffer.has_value() && renderedBuffer.value() != nullptr) {
        if (m_visibleWPEView != nullptr) {
            Logging::logDebug("onTransactionCommittedOnBrowserThread: buffer_rendered %p", renderedBuffer.value());
            wpe_view_buffer_rendered(m_visibleWPEView, WPE_BUFFER(renderedBuffer.value()));
        }
    }

    if (m_numTransactionCommitOrAckPending > 0)
        m_numTransactionCommitOrAckPending--;

    // Apply next queued transaction if any
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

    // Previous buffer will be released in onCompleteCallback
    WPEBufferAndroid* bufferToRelease = m_currentFrameBuffer;

    // Take ownership of the new buffer
    m_currentFrameBuffer = wpeBuffer;
    g_object_ref(m_currentFrameBuffer);

    std::weak_ptr<RendererSurfaceControl> const weakPtr(shared_from_this());

    // Complete callback - release previous buffer
    if (bufferToRelease != nullptr)
        g_object_ref(bufferToRelease);

    transaction.setOnCompleteCallback([weakPtr, buffer = bufferToRelease](SurfaceControl::TransactionStats&& stats) {
        auto ptr = weakPtr.lock();
        if (ptr)
            ptr->onTransActionAckOnBrowserThread(buffer, std::move(stats));
        // Always unref - callback owns this ref
        if (buffer != nullptr)
            g_object_unref(buffer);
    });

    // Commit callback - notify buffer rendered
    g_object_ref(wpeBuffer);

    transaction.setOnCommitCallback([weakPtr, buffer = wpeBuffer]() {
        auto ptr = weakPtr.lock();
        if (ptr)
            ptr->onTransactionCommittedOnBrowserThread(buffer);
        // Always unref - callback owns this ref
        if (buffer != nullptr)
            g_object_unref(buffer);
    });

    // Queue transaction if one is already pending; otherwise apply immediately
    if (m_numTransactionCommitOrAckPending > 0) {
        m_pendingTransactionQueue.push(std::move(transaction));
    } else {
        m_numTransactionCommitOrAckPending++;
        transaction.apply();
    }
}
