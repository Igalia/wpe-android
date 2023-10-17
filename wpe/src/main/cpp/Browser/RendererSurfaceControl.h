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

#pragma once

#include "Renderer.h"

#include <map>
#include <memory>
#include <wpe-android/view-backend.h>

#include "SurfaceControl.h"

class RendererSurfaceControl final : public Renderer, public std::enable_shared_from_this<RendererSurfaceControl> {
public:
    RendererSurfaceControl(WPEAndroidViewBackend* viewBackend, uint32_t width, uint32_t height);
    ~RendererSurfaceControl() override;

    RendererSurfaceControl(RendererSurfaceControl&&) = delete;
    RendererSurfaceControl& operator=(RendererSurfaceControl&&) = delete;
    RendererSurfaceControl(const RendererSurfaceControl&) = delete;
    RendererSurfaceControl& operator=(const RendererSurfaceControl&) = delete;

    uint32_t width() const noexcept override { return m_size.m_width; }
    uint32_t height() const noexcept override { return m_size.m_height; }

    void onSurfaceCreated(ANativeWindow* window) noexcept override;
    void onSurfaceChanged(int format, uint32_t width, uint32_t height) noexcept override;
    void onSurfaceRedrawNeeded() noexcept override;
    void onSurfaceDestroyed() noexcept override;

    void commitBuffer(std::shared_ptr<ScopedWPEAndroidBuffer> buffer, std::shared_ptr<ScopedFD> fenceFD) override;

private:
    struct ResourceRef {
        ResourceRef() = default;
        ~ResourceRef() = default;

        ResourceRef(const ResourceRef& other) = default;
        ResourceRef& operator=(const ResourceRef& other) = default;

        ResourceRef(ResourceRef&& other) = default;
        ResourceRef& operator=(ResourceRef&& other) = default;

        std::shared_ptr<SurfaceControl::Surface> m_surface;
        std::shared_ptr<ScopedWPEAndroidBuffer> m_scopedBuffer;
    };
    using ResourceRefs = std::map<ASurfaceControl*, ResourceRef>;

    void onTransActionAckOnBrowserThread(ResourceRefs releasedResources, SurfaceControl::TransactionStats stats);
    void onTransactionCommittedOnBrowserThread();

    void processTransactionQueue();

    WPEAndroidViewBackend* m_viewBackend = nullptr;
    std::shared_ptr<SurfaceControl::Surface> m_surface;

    struct {
        uint32_t m_width;
        uint32_t m_height;
    } m_size = {};

    std::queue<SurfaceControl::Transaction> m_pendingTransactionQueue;
    uint32_t m_numTransactionCommitOrAckPending = 0U;

    ResourceRefs m_currentFrameResources;

    std::queue<std::shared_ptr<ScopedWPEAndroidBuffer>> m_releaseBufferQueue;
};
