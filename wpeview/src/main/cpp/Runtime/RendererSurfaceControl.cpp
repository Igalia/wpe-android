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

#include <wpe-platform/wpe/WPEBufferAndroid.h>
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

    // Release m_frontBuffer ref
    if (m_frontBuffer != nullptr) {
        g_object_unref(m_frontBuffer);
        m_frontBuffer = nullptr;
    }

    // Release m_currentFrameBuffer ref
    if (m_currentFrameBuffer.has_value() && m_currentFrameBuffer.value() != nullptr) {
        g_object_unref(m_currentFrameBuffer.value());
        m_currentFrameBuffer = std::nullopt;
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
                ptr->onTransactionCommittedOnBrowserThread(std::nullopt); // Redraw, not a new frame
        };
        transaction.setOnCommitCallback(std::move(onCommitCallback));

        m_numTransactionCommitOrAckPending++;
        transaction.apply();

        return;
    }

    Logging::logDebug("No buffer available");
}

// Release the surface control when the Android surface is destroyed
void RendererSurfaceControl::onSurfaceDestroyed() noexcept
{
    // Clear frontBuffer since surface is gone
    if (m_frontBuffer != nullptr) {
        g_object_unref(m_frontBuffer);
        m_frontBuffer = nullptr;
    }
    m_surface.reset();
}

// Commit a new buffer to the surface or defer if surface is unavailable
void RendererSurfaceControl::commitBuffer(
    AHardwareBuffer* hardwareBuffer, WPEBufferAndroid* wpeBuffer, std::shared_ptr<ScopedFD> fenceFD)
{
    // Defer commit until surface is restored
    if (m_surface == nullptr) {
        // Release old pending commit buffer if exists
        if (m_pendingCommitBuffer != nullptr && m_wpeView != nullptr) {
            wpe_view_buffer_released(m_wpeView, WPE_BUFFER(m_pendingCommitBuffer));
            g_object_unref(m_pendingCommitBuffer);
            m_pendingCommitBuffer = nullptr;
        }

        // Store new buffer as pending
        m_pendingCommitBuffer = wpeBuffer;
        g_object_ref(wpeBuffer);
        m_pendingCommitFenceFD = fenceFD;

        // Release current frame buffer since we can't display anything
        if (m_currentFrameBuffer.has_value() && m_currentFrameBuffer.value() != nullptr) {
            auto* buffer = m_currentFrameBuffer.value();
            if (m_wpeView != nullptr) {
                wpe_view_buffer_released(m_wpeView, WPE_BUFFER(buffer));
            }
            g_object_unref(buffer);
            m_currentFrameBuffer = std::nullopt;
        }
        // Clear frontBuffer, releasing our ref
        if (m_frontBuffer != nullptr) {
            g_object_unref(m_frontBuffer);
            m_frontBuffer = nullptr;
        }
        return;
    }

    // Surface available - release any pending buffer from when surface was unavailable
    if (m_pendingCommitBuffer != nullptr) {
        if (m_wpeView != nullptr)
            wpe_view_buffer_released(m_wpeView, WPE_BUFFER(m_pendingCommitBuffer));
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

        // Clear m_frontBuffer if it matches, releasing our ref
        if (m_frontBuffer == buffer) {
            g_object_unref(m_frontBuffer);
            m_frontBuffer = nullptr;
        }

        // Notify WebKit that the buffer can be reused
        if (m_wpeView != nullptr) {
            Logging::logDebug("onTransActionAckOnBrowserThread: buffer_released %p", buffer);
            wpe_view_buffer_released(m_wpeView, WPE_BUFFER(buffer));
        }
        // Note: Callback handles g_object_unref for the captured buffer
    }
}

// Process transaction commit completion, trigger buffer rendered callback, and apply queued transactions
void RendererSurfaceControl::onTransactionCommittedOnBrowserThread(std::optional<WPEBufferAndroid*> renderedBuffer)
{
    // Update m_frontBuffer and notify WebKit (for new frames only, not redraws)
    if (renderedBuffer.has_value() && renderedBuffer.value() != nullptr) {
        // Release old frontBuffer ref if any
        if (m_frontBuffer != nullptr) {
            g_object_unref(m_frontBuffer);
        }
        // Set new frontBuffer WITH ref to prevent dangling pointer
        m_frontBuffer = renderedBuffer.value();
        g_object_ref(m_frontBuffer);

        if (m_wpeView != nullptr) {
            Logging::logDebug("onTransactionCommittedOnBrowserThread: buffer_rendered %p", renderedBuffer.value());
            wpe_view_buffer_rendered(m_wpeView, WPE_BUFFER(renderedBuffer.value()));
        }
    }

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

    // Ref buffer for complete callback capture to prevent dangling pointer if m_currentFrameBuffer is released
    WPEBufferAndroid* releaseBuffer = bufferToRelease.value_or(nullptr);
    if (releaseBuffer != nullptr)
        g_object_ref(releaseBuffer);

    auto onCompleteCallback = [weakPtr, buffer = releaseBuffer](SurfaceControl::TransactionStats&& stats) {
        auto ptr = weakPtr.lock();
        if (ptr)
            ptr->onTransActionAckOnBrowserThread(buffer, std::move(stats));
        // Always unref - callback owns this ref
        if (buffer != nullptr)
            g_object_unref(buffer);
    };
    transaction.setOnCompleteCallback(std::move(onCompleteCallback));

    // Ref buffer for commit callback capture to prevent dangling pointer if buffer is released before callback fires
    if (wpeBuffer != nullptr)
        g_object_ref(wpeBuffer);

    auto onCommitCallback = [weakPtr, buffer = wpeBuffer]() {
        auto ptr = weakPtr.lock();
        if (ptr)
            ptr->onTransactionCommittedOnBrowserThread(buffer);
        // Always unref - callback owns this ref
        if (buffer != nullptr)
            g_object_unref(buffer);
    };
    transaction.setOnCommitCallback(std::move(onCommitCallback));

    // Queue transaction if one is already pending; otherwise apply immediately
    // Note: m_frontBuffer is updated in onTransactionCommittedOnBrowserThread, not here
    if (m_numTransactionCommitOrAckPending > 0) {
        m_pendingTransactionQueue.push(std::move(transaction));
    } else {
        m_numTransactionCommitOrAckPending++;
        transaction.apply();
    }
}
