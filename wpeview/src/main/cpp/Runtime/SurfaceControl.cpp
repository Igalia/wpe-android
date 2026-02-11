/**
 * Copyright (C) 2023 Igalia S.L. <info@igalia.com>
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

#include "SurfaceControl.h"

#include "WKRuntime.h"

namespace {

struct TransactionContext {
    SurfaceControl::Transaction::OnCompleteCallback m_completeCallback;
    SurfaceControl::Transaction::OnCommitCallback m_commitCallback;
    SurfaceControl::TransactionStats m_stats;
};

SurfaceControl::TransactionStats toTransactionStats(ASurfaceTransactionStats* stats)
{
    SurfaceControl::TransactionStats transactionStats;
    if (stats == nullptr)
        return transactionStats;

    ASurfaceControl** surfaceControls = nullptr;
    size_t size = 0U;
    ASurfaceTransactionStats_getASurfaceControls(stats, &surfaceControls, &size);
    transactionStats.m_surfaceStats.resize(size);
    for (size_t i = 0U; i < size; ++i) {
        transactionStats.m_surfaceStats[i].m_surface = surfaceControls[i];
        int const fenceFD = ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, surfaceControls[i]);
        if (fenceFD != -1) {
            transactionStats.m_surfaceStats[i].m_fence = std::make_shared<ScopedFD>(fenceFD);
        }
    }
    ASurfaceTransactionStats_releaseASurfaceControls(surfaceControls);

    return transactionStats;
}

void onTransactionCommitedOnAnyThread(void* context, ASurfaceTransactionStats* /*stats*/)
{
    auto* ackCtx = static_cast<TransactionContext*>(context);

    // API documentation states that this callback can be dispatched from any thread.
    // Let's relay the transaction completion to the UI thread.
    WKRuntime::instance().invokeOnUiThread(
        +[](void* userData) {
            auto* ackCtx = reinterpret_cast<TransactionContext*>(userData);
            std::move(ackCtx->m_commitCallback)();
        },
        // NOLINTNEXTLINE(cppcoreguidelines-owning-memory)
        +[](void* userData) { delete reinterpret_cast<TransactionContext*>(userData); }, ackCtx);
}

void onTransactionCompletedOnAnyThread(void* context, ASurfaceTransactionStats* stats)
{
    auto* ackCtx = static_cast<TransactionContext*>(context);
    ackCtx->m_stats = toTransactionStats(stats);

    // API documentation states that this callback can be dispatched from any thread.
    // Let's relay the transaction completion to the UI thread.
    WKRuntime::instance().invokeOnUiThread(
        +[](void* userData) {
            auto* ackCtx = reinterpret_cast<TransactionContext*>(userData);
            std::move(ackCtx->m_completeCallback)(std::move(ackCtx->m_stats));
        },
        // NOLINTNEXTLINE(cppcoreguidelines-owning-memory)
        +[](void* userData) { delete reinterpret_cast<TransactionContext*>(userData); }, ackCtx);
}

} // namespace

SurfaceControl::Surface::Surface(ANativeWindow* parent, const char* name)
{
    m_surfaceControl = ASurfaceControl_createFromWindow(parent, name);
}

SurfaceControl::Surface::~Surface()
{
    ASurfaceControl_release(m_surfaceControl);
    m_surfaceControl = nullptr;
}

SurfaceControl::Surface::Surface(Surface&& other) noexcept
    : m_surfaceControl(other.m_surfaceControl)
{
    other.m_surfaceControl = nullptr;
}

SurfaceControl::Surface& SurfaceControl::Surface::operator=(Surface&& other) noexcept
{
    if (this == &other)
        return *this;

    m_surfaceControl = other.m_surfaceControl;
    other.m_surfaceControl = nullptr;
    return *this;
}

SurfaceControl::Transaction::Transaction() { m_transaction = ASurfaceTransaction_create(); }

SurfaceControl::Transaction::~Transaction() { destroyIfNeeded(); }

void SurfaceControl::Transaction::destroyIfNeeded()
{
    if (m_transaction == nullptr)
        return;
    ASurfaceTransaction_delete(m_transaction);
    m_transaction = nullptr;
}

SurfaceControl::Transaction::Transaction(Transaction&& other) noexcept
    : m_transaction(other.m_transaction)
{
    other.m_transaction = nullptr;
}

SurfaceControl::Transaction& SurfaceControl::Transaction::operator=(Transaction&& other) noexcept
{
    if (this == &other)
        return *this;

    destroyIfNeeded();
    m_transaction = other.m_transaction;
    other.m_transaction = nullptr;
    return *this;
}

void SurfaceControl::Transaction::setVisibility(const Surface& surface, ASurfaceTransactionVisibility visibility)
{
    ASurfaceTransaction_setVisibility(m_transaction, surface.surfaceControl(), visibility);
}

void SurfaceControl::Transaction::setZOrder(const Surface& surface, int32_t zOrder)
{
    ASurfaceTransaction_setZOrder(m_transaction, surface.surfaceControl(), zOrder);
}

void SurfaceControl::Transaction::setBuffer(const Surface& surface, AHardwareBuffer* buffer, int fenceFD)
{
    ASurfaceTransaction_setBuffer(m_transaction, surface.surfaceControl(), buffer, fenceFD);
}

void SurfaceControl::Transaction::setParent(const Surface& surface, Surface* newParent)
{
    ASurfaceTransaction_reparent(
        m_transaction, surface.surfaceControl(), newParent != nullptr ? newParent->surfaceControl() : nullptr);
}

void SurfaceControl::Transaction::setOnCompleteCallback(OnCompleteCallback callback)
{
    m_onCompleteCallback = std::move(callback);
}

void SurfaceControl::Transaction::setOnCommitCallback(OnCommitCallback callback)
{
    m_onCommitCallback = std::move(callback);
}

void SurfaceControl::Transaction::apply()
{
    if (m_onCommitCallback != nullptr) {
        auto ackCtx = std::make_unique<TransactionContext>();
        ackCtx->m_commitCallback = std::move(m_onCommitCallback);
        ASurfaceTransaction_setOnCommit(m_transaction, ackCtx.release(), onTransactionCommitedOnAnyThread);
    }

    if (m_onCompleteCallback != nullptr) {
        auto ackCtx = std::make_unique<TransactionContext>();
        ackCtx->m_completeCallback = std::move(m_onCompleteCallback);

        ASurfaceTransaction_setOnComplete(m_transaction, ackCtx.release(), onTransactionCompletedOnAnyThread);
    }

    ASurfaceTransaction_apply(m_transaction);
}
