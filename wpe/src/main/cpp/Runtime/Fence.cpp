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

#include "Fence.h"

#include <algorithm>
#include <android/sync.h>
#include <memory>
#include <poll.h>

using sync_file_info_data = struct sync_file_info;

Fence::FenceStatus Fence::getStatus(int fileDescriptor)
{
    auto info = std::unique_ptr<sync_file_info_data, void (*)(sync_file_info_data*)> {
        sync_file_info(fileDescriptor), sync_file_info_free};
    if (info == nullptr) {
        return FenceStatus::Invalid;
    }

    if (info->status != 1) {
        return FenceStatus::NotSignaled;
    }

    __u64 timestamp = 0U;
    auto* fenceInfo = sync_get_fence_info(info.get());
    for (size_t i = 0; i < info->num_fences; i++) {
        timestamp = std::max(timestamp, fenceInfo->timestamp_ns);
    }

    return FenceStatus::Signaled;
}
