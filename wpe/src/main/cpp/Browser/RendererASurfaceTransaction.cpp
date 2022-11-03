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

#include "RendererASurfaceTransaction.h"

#include "Browser.h"
#include "Logging.h"

#include <cassert>

RendererASurfaceTransaction::RendererASurfaceTransaction(
    wpe_android_view_backend_exportable* viewBackendExportable, uint32_t width, uint32_t height)
    : m_viewBackendExportable(viewBackendExportable)
    , m_size({width, height})
{
    Logging::logDebug(
        "RendererASurfaceTransaction(%p, %u, %u)", m_viewBackendExportable, m_size.m_width, m_size.m_height);
}

RendererASurfaceTransaction::~RendererASurfaceTransaction()
{
    Logging::logDebug("~RendererASurfaceTransaction()");
    if (m_surfaceControl != nullptr) {
        ASurfaceControl_release(m_surfaceControl);
        m_surfaceControl = nullptr;
    }

    // Release the stored exported buffer, if any, and if different from the locked buffer.
    // If the same, the buffer will be released via the locked buffer.
    if (m_state.m_exportedBuffer && (m_state.m_exportedBuffer != m_state.m_lockedBuffer))
        releaseExportedBuffer(*m_state.m_exportedBuffer);

    // If locked buffer still exists, release it.
    if (m_state.m_lockedBuffer)
        releaseExportedBuffer(*m_state.m_lockedBuffer);

    m_state = {};
}

void RendererASurfaceTransaction::onSurfaceCreated(ANativeWindow* window) noexcept
{
    m_surfaceControl = ASurfaceControl_createFromWindow(window, "RendererASurfaceTransaction");
}

void RendererASurfaceTransaction::onSurfaceChanged(int /*format*/, uint32_t width, uint32_t height) noexcept
{
    m_size.m_width = width;
    m_size.m_height = height;
}

void RendererASurfaceTransaction::onSurfaceRedrawNeeded() noexcept
{
    // Nothing is doable if there's no ASurfaceControl.
    if ((m_surfaceControl != nullptr) && m_state.m_exportedBuffer) {
        // NOLINTNEXTLINE(cppcoreguidelines-owning-memory, bugprone-unhandled-exception-at-new)
        scheduleFrame(new TransactionContext {shared_from_this(), m_state.m_exportedBuffer});
    }
}

void RendererASurfaceTransaction::onSurfaceDestroyed() noexcept
{
    ASurfaceControl_release(m_surfaceControl);
    m_surfaceControl = nullptr;
}

void RendererASurfaceTransaction::handleExportedBuffer(std::shared_ptr<ExportedBuffer> buffer) noexcept
{
    // If there's an exported buffer being held that's different from the locked one, it has to
    // be released here since it won't be released otherwise.
    if (m_state.m_exportedBuffer && (m_state.m_exportedBuffer != m_state.m_lockedBuffer))
        releaseExportedBuffer(*m_state.m_exportedBuffer);

    m_state.m_exportedBuffer = std::move(buffer);

    // Each buffer export requires a corresponding frame-complete callback. This is signalled here.
    m_state.m_dispatchFrameCompleteCallback = true;

    // Nothing is doable if there's no ASurfaceControl.
    if (m_surfaceControl != nullptr) {
        // NOLINTNEXTLINE(cppcoreguidelines-owning-memory, bugprone-unhandled-exception-at-new)
        scheduleFrame(new TransactionContext {shared_from_this(), m_state.m_exportedBuffer});
    }
}

void RendererASurfaceTransaction::scheduleFrame(TransactionContext* transactionContext)
{
    assert(m_surfaceControl);

    // Take the TransactionContext and its buffer and form a transaction for it.
    // Upon transaction completion, the buffer will be presented on the surface.
    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    ASurfaceTransaction_setVisibility(transaction, m_surfaceControl, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
    ASurfaceTransaction_setZOrder(transaction, m_surfaceControl, 0);

    ASurfaceTransaction_setBuffer(transaction, m_surfaceControl, transactionContext->m_buffer->buffer());

    ASurfaceTransaction_setOnComplete(transaction, transactionContext, onTransactionCompleteOnAnyThread);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
}

void RendererASurfaceTransaction::finishFrame(std::shared_ptr<ExportedBuffer> buffer)
{
    Logging::logDebug("RendererASurfaceTransaction::finishFrame(%p)", buffer.get());

    // If the current locked buffer is different from the current exported one, it should be released here.
    if (m_state.m_lockedBuffer && (m_state.m_exportedBuffer != m_state.m_lockedBuffer))
        releaseExportedBuffer(*m_state.m_lockedBuffer);

    // The just-presented buffer is now also the locked one.
    m_state.m_lockedBuffer = std::move(buffer);

    // If the frame-complete callback dispatch was requested, it's invoked here.
    if (m_state.m_dispatchFrameCompleteCallback) {
        wpe_android_view_backend_exportable_dispatch_frame_complete(m_viewBackendExportable);
        m_state.m_dispatchFrameCompleteCallback = false;
    }
}

void RendererASurfaceTransaction::releaseExportedBuffer(const ExportedBuffer& buffer) const noexcept
{
    wpe_android_view_backend_exportable_dispatch_release_buffer(
        m_viewBackendExportable, buffer.buffer(), buffer.poolId(), buffer.bufferId());
}

void RendererASurfaceTransaction::onTransactionCompleteOnAnyThread(
    void* context, ASurfaceTransactionStats* /*stats*/) noexcept
{
    Logging::logDebug("RendererASurfaceTransaction::onTransactionCompleteOnAnyThread(%p)", context);

    // API documentation states that this callback can be dispatched from any thread.
    // Let's relay the transaction completion to the UI thread.
    Browser::instance().invokeOnUiThread(
        +[](void* userData) {
            auto* transactionContext = reinterpret_cast<TransactionContext*>(userData);
            if (auto renderer = transactionContext->m_renderer.lock())
                renderer->finishFrame(transactionContext->m_buffer);
        },
        // NOLINTNEXTLINE(cppcoreguidelines-owning-memory)
        +[](void* userData) { delete reinterpret_cast<TransactionContext*>(userData); }, context);
}
