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

#include <android/surface_control.h>
#include <memory>
#include <wpe-android/view-backend-exportable.h>

class RendererASurfaceTransaction final : public Renderer,
                                          public std::enable_shared_from_this<RendererASurfaceTransaction> {
public:
    RendererASurfaceTransaction(
        wpe_android_view_backend_exportable* viewBackendExportable, uint32_t width, uint32_t height);
    ~RendererASurfaceTransaction() override;

    RendererASurfaceTransaction(RendererASurfaceTransaction&&) = delete;
    RendererASurfaceTransaction& operator=(RendererASurfaceTransaction&&) = delete;
    RendererASurfaceTransaction(const RendererASurfaceTransaction&) = delete;
    RendererASurfaceTransaction& operator=(const RendererASurfaceTransaction&) = delete;

    uint32_t width() const noexcept override { return m_size.m_width; }
    uint32_t height() const noexcept override { return m_size.m_height; }

    void onSurfaceCreated(ANativeWindow* window) noexcept override;
    void onSurfaceChanged(int format, uint32_t width, uint32_t height) noexcept override;
    void onSurfaceRedrawNeeded() noexcept override;
    void onSurfaceDestroyed() noexcept override;

    void handleExportedBuffer(std::shared_ptr<ExportedBuffer> buffer) noexcept override;

private:
    struct TransactionContext {
        std::weak_ptr<RendererASurfaceTransaction> m_renderer {};
        std::shared_ptr<ExportedBuffer> m_buffer {};
    };

    void scheduleFrame(TransactionContext* transactionContext);
    void finishFrame(std::shared_ptr<ExportedBuffer> buffer);
    void releaseExportedBuffer(const ExportedBuffer& buffer) const noexcept;

    static void onTransactionCompleteOnAnyThread(void* context, ASurfaceTransactionStats* stats) noexcept;

    wpe_android_view_backend_exportable* m_viewBackendExportable = nullptr;
    ASurfaceControl* m_surfaceControl = nullptr;

    struct {
        uint32_t m_width;
        uint32_t m_height;
    } m_size = {};

    struct {
        bool m_dispatchFrameCompleteCallback = false;
        std::shared_ptr<ExportedBuffer> m_exportedBuffer {};
        std::shared_ptr<ExportedBuffer> m_lockedBuffer {};
    } m_state;
};
