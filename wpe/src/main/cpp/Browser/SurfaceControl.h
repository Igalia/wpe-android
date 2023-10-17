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

#pragma once

#include <android/native_window.h>
#include <android/surface_control.h>
#include <queue>

#include "ScopedFD.h"

class ScopedWPEAndroidBuffer;

class SurfaceControl final {
public:
    class Surface final {
    public:
        Surface(ANativeWindow* parent, const char* name);

        Surface(const Surface&) = delete;
        Surface& operator=(const Surface&) = delete;

        ~Surface();

        Surface(Surface&& other) noexcept;
        Surface& operator=(Surface&& other) noexcept;

        ASurfaceControl* surfaceControl() const { return m_surfaceControl; }

    private:
        ASurfaceControl* m_surfaceControl = nullptr;
    };

    struct SurfaceStats final {
        SurfaceStats() = default;
        ~SurfaceStats() = default;

        SurfaceStats(const SurfaceStats&) = delete;
        SurfaceStats& operator=(const SurfaceStats&) = delete;

        SurfaceStats(SurfaceStats&& other) = default;
        SurfaceStats& operator=(SurfaceStats&& other) = default;

        ASurfaceControl* m_surface = nullptr;
        std::shared_ptr<ScopedFD> m_fence;
    };

    struct TransactionStats final {
    public:
        TransactionStats() = default;
        ~TransactionStats() = default;

        TransactionStats(const TransactionStats&) = delete;
        TransactionStats& operator=(const TransactionStats&) = delete;

        TransactionStats(TransactionStats&& other) = default;
        TransactionStats& operator=(TransactionStats&& other) = default;

        std::vector<SurfaceStats> m_surfaceStats;
    };

    class Transaction final {
    public:
        Transaction();

        Transaction(const Transaction&) = delete;
        Transaction& operator=(const Transaction&) = delete;

        ~Transaction();

        Transaction(Transaction&& other) noexcept;
        Transaction& operator=(Transaction&& other) noexcept;

        void setVisibility(const Surface& surface, int8_t visibility);
        void setZOrder(const Surface& surface, int32_t zOrder);
        void setBuffer(const Surface& surface, AHardwareBuffer* buffer, int fenceFD);

        void apply();

        using OnCompleteCallback = std::function<void(TransactionStats stats)>;
        void setOnCompleteCallback(OnCompleteCallback callback);

        using OnCommitCallback = std::function<void()>;
        void setOnCommitCallback(OnCommitCallback callback);

    private:
        void destroyIfNeeded();

        ASurfaceTransaction* m_transaction = nullptr;

        OnCompleteCallback m_onCompleteCallback;
        OnCommitCallback m_onCommitCallback;
    };
};
