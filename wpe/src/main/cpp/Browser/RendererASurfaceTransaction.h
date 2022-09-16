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

struct ANativeWindow;
struct ASurfaceControl;
struct ASurfaceTransactionStats;

class Page;

class RendererASurfaceTransaction final : public Renderer {
public:
    RendererASurfaceTransaction(Page&, unsigned width, unsigned height);
    virtual ~RendererASurfaceTransaction();

    int width() const override { return m_size.width; }
    int height() const override { return m_size.height; }

    virtual void surfaceCreated(ANativeWindow*) override;
    virtual void surfaceChanged(int format, unsigned width, unsigned height) override;
    virtual void surfaceRedrawNeeded() override;
    virtual void surfaceDestroyed() override;

    virtual void handleExportedBuffer(const std::shared_ptr<ExportedBuffer>&) override;

private:
    struct TransactionContext;
    void scheduleFrame(TransactionContext*);
    void finishFrame(const std::shared_ptr<ExportedBuffer>&);

    static void onTransactionCompleteOnAnyThread(void* data, ASurfaceTransactionStats* stats);

    Page& m_page;
    struct {
        ANativeWindow* window {nullptr};
        ASurfaceControl* control {nullptr};
    } m_surface;

    struct {
        unsigned width;
        unsigned height;
    } m_size;

    struct {
        bool dispatchFrameCompleteCallback {false};

        std::shared_ptr<ExportedBuffer> exportedBuffer;
        std::shared_ptr<ExportedBuffer> lockedBuffer;
    } m_state;
};
