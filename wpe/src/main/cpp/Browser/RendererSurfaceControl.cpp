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

#include <cassert>
#include <utility>

#include "Browser.h"
#include "Fence.h"
#include "Logging.h"
#include "SurfaceControl.h"

RendererSurfaceControl::RendererSurfaceControl(WPEAndroidViewBackend* viewBackend, uint32_t width, uint32_t height)
    : m_viewBackend(viewBackend)
    , m_size({width, height})
{
    Logging::logDebug("RendererSurfaceControl(%p, %u, %u)", m_viewBackend, m_size.m_width, m_size.m_height);
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
    if (m_surface != nullptr) {
        if (m_pendingCommitBuffer != nullptr && m_pendingCommitFenceFD != nullptr) {
            Logging::logDebug("RendererSurfaceControl::onSurfaceRedrawNeeded - sending pending commit");
            auto buffer = m_pendingCommitBuffer;
            auto fence = m_pendingCommitFenceFD;
            commitBuffer(buffer, fence);
        } else if (m_frontBuffer != nullptr && m_pendingFrontBufferRedraw) {
            auto fence = std::make_shared<ScopedFD>(-1);
            Logging::logDebug("RendererSurfaceControl::onSurfaceRedrawNeeded - front buffer commit");
            commitBuffer(m_frontBuffer, fence);
        }
    }
}

void RendererSurfaceControl::onSurfaceDestroyed() noexcept
{
    m_surface.reset();
    m_pendingFrontBufferRedraw = true;
}

void RendererSurfaceControl::commitBuffer(
    std::shared_ptr<ScopedWPEAndroidBuffer> buffer, std::shared_ptr<ScopedFD> fenceFD)
{
    if (m_surface == nullptr) { // surface is lost
        if (m_pendingCommitBuffer != nullptr)
            WPEAndroidViewBackend_dispatchReleaseBuffer(m_viewBackend, m_pendingCommitBuffer->wpeBuffer());

        m_pendingCommitBuffer = buffer;
        m_pendingCommitFenceFD = fenceFD;
        if (m_frontBuffer != nullptr) {
            WPEAndroidViewBackend_dispatchReleaseBuffer(m_viewBackend, m_frontBuffer->wpeBuffer());
            m_frontBuffer = nullptr;
        }
        return;
    }

    if (m_pendingCommitBuffer != nullptr) {
        m_pendingCommitBuffer = nullptr;
        m_pendingCommitFenceFD = nullptr;
    }

    m_pendingFrontBufferRedraw = false;

    SurfaceControl::Transaction transaction;
    transaction.setVisibility(*m_surface, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    transaction.setZOrder(*m_surface, 0);
    transaction.setBuffer(*m_surface, buffer->buffer(), fenceFD->release());

    ResourceRefs resourcesToRelease;
    resourcesToRelease.swap(m_currentFrameResources);
    m_currentFrameResources.clear();

    auto& resourceRef = m_currentFrameResources[m_surface->surfaceControl()];
    resourceRef.m_surface = m_surface;
    resourceRef.m_scopedBuffer = buffer;

    std::weak_ptr<RendererSurfaceControl> const weakPtr(shared_from_this());
    auto onCompleteCallback = [weakPtr, resources = std::move(resourcesToRelease)](auto&& stats) {
        auto ptr = weakPtr.lock();
        if (ptr)
            ptr->onTransActionAckOnBrowserThread(resources, std::forward<decltype(stats)>(stats));
    };
    transaction.setOnCompleteCallback(std::move(onCompleteCallback));

    auto onCommitCallback = [weakPtr]() {
        auto ptr = weakPtr.lock();
        if (ptr)
            ptr->onTransactionCommittedOnBrowserThread();
    };
    transaction.setOnCommitCallback(std::move(onCommitCallback));

    if (m_numTransactionCommitOrAckPending > 0) {
        m_pendingTransactionQueue.push(std::move(transaction));
    } else {
        m_numTransactionCommitOrAckPending++;
        transaction.apply();
        m_frontBuffer = buffer;
    }
}

void RendererSurfaceControl::onTransActionAckOnBrowserThread(
    ResourceRefs releasedResources, SurfaceControl::TransactionStats stats)
{
    for (auto& surfaceStat : stats.m_surfaceStats) {
        auto resourceIterator = releasedResources.find(surfaceStat.m_surface);
        if (resourceIterator == releasedResources.end()) {
            continue;
        }

        resourceIterator->second.m_scopedBuffer->setReleaseFenceFD(surfaceStat.m_fence);
        m_releaseBufferQueue.push(std::move(resourceIterator->second.m_scopedBuffer));
    }
    releasedResources.clear();

    // Following is from Android ASurfaceControl documentation in surface_control.h
    //
    // Each time a buffer is set through ASurfaceTransaction_setBuffer() on a transaction
    // which is applied, the framework takes a ref on this buffer. The framework treats the
    // addition of a buffer to a particular surface as a unique ref. When a transaction updates or
    // removes a buffer from a surface, or removes the surface itself from the tree, this ref is
    // guaranteed to be released in the OnComplete callback for this transaction. The
    // ASurfaceControlStats provided in the callback for this surface may contain an optional fence
    // which must be signaled before the ref is assumed to be released.
    //
    // The client must ensure that all pending refs on a buffer are released before attempting to reuse
    // this buffer, otherwise synchronization errors may occur.
    //
    // TBD: Based on above description it seems that fence must be checked and waited if present before reusing the
    // buffer. But in practice it seems to have no significant effect if check is done or not done
    while (!m_releaseBufferQueue.empty()) {
        auto& pendingBuffer = m_releaseBufferQueue.front();
        auto status = pendingBuffer->getReleaseFenceFD() != -1 ? Fence::getStatus(pendingBuffer->getReleaseFenceFD())
                                                               : Fence::Invalid;

        if (status == Fence::NotSignaled)
            break;

        if (m_frontBuffer && m_frontBuffer->wpeBuffer() == pendingBuffer->wpeBuffer())
            m_frontBuffer = nullptr;

        WPEAndroidViewBackend_dispatchReleaseBuffer(m_viewBackend, pendingBuffer->wpeBuffer());
        m_releaseBufferQueue.pop();
    }
}

void RendererSurfaceControl::onTransactionCommittedOnBrowserThread()
{
    // We can notify WebProcess already at this point to start rendering next frame.
    // This causes WPEBackend-android to use triple buffering but performance is better this way
    //
    // If this dispatch_frame_complete is called in onTransActionAckOnBrowserThread then WPEBackend-android
    // stays in double buffering
    WPEAndroidViewBackend_dispatchFrameComplete(m_viewBackend);

    processTransactionQueue();
}

void RendererSurfaceControl::processTransactionQueue()
{
    m_numTransactionCommitOrAckPending--;
    if (!m_pendingTransactionQueue.empty()) {
        m_numTransactionCommitOrAckPending++;
        m_pendingTransactionQueue.front().apply();
        m_pendingTransactionQueue.pop();
    }
}
