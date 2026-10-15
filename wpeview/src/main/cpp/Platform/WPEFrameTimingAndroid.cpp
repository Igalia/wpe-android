/**
 * Copyright (C) 2026 Igalia S.L.
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

#include "WPEFrameTimingAndroid.h"

#include <algorithm>
#include <android/sync.h>
#include <android/trace.h>
#include <atomic>
#include <cinttypes>
#include <condition_variable>
#include <cstdio>
#include <ctime>
#include <deque>
#include <mutex>
#include <poll.h>
#include <thread>
#include <unistd.h>

namespace {

constexpr char inFlightSectionName[] = "WPEFrame in flight";

struct CompletedFrame {
    WPEFrameTiming::Frame frame;
    int64_t acquireTime;
    int64_t latchTime;
    int presentFence;
};

std::atomic<uint64_t> lastFrameNumber {0};
std::mutex queueLock;
std::condition_variable queueCondition;
std::deque<CompletedFrame> completedFrames;

int64_t monotonicTime()
{
    timespec now {};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<int64_t>(now.tv_sec) * 1000000000 + now.tv_nsec;
}

// When the fence signaled, in CLOCK_MONOTONIC ns, or -1.
int64_t fenceSignalTime(int fd)
{
    pollfd request {fd, POLLIN, 0};
    if (poll(&request, 1, 1000) <= 0)
        return -1;

    struct sync_file_info* info = sync_file_info(fd);
    if (!info)
        return -1;
    int64_t signalTime = -1;
    if (info->status == 1) {
        struct sync_fence_info* fences = sync_get_fence_info(info);
        for (uint32_t i = 0; i < info->num_fences; ++i)
            signalTime = std::max(signalTime, static_cast<int64_t>(fences[i].timestamp_ns));
    }
    sync_file_info_free(info);
    return signalTime;
}

// The present fence often signals only after the completion callback has run, and the binder thread
// running the callbacks must not wait for it: a thread of its own waits, in submission order.
[[noreturn]] void waitForPresentFences()
{
    while (true) {
        CompletedFrame completed;
        {
            std::unique_lock lock(queueLock);
            queueCondition.wait(lock, [] { return !completedFrames.empty(); });
            completed = completedFrames.front();
            completedFrames.pop_front();
        }

        int64_t presentTime = -1;
        if (completed.presentFence >= 0) {
            presentTime = fenceSignalTime(completed.presentFence);
            close(completed.presentFence);
        }

        // "clock" is the time of this section, which maps the CLOCK_MONOTONIC times above to
        // the trace clock.
        char name[256];
        int length = snprintf(name, sizeof(name),
            "WPEFrame %" PRIu64 " apply %" PRId64 " acquire %" PRId64 " latch %" PRId64 " present %" PRId64
            " clock %" PRId64,
            completed.frame.number, completed.frame.applyTime, completed.acquireTime, completed.latchTime, presentTime,
            monotonicTime());
        if (length <= 0)
            continue;
        ATrace_beginSection(name);
        ATrace_endSection();
    }
}

} // namespace

namespace WPEFrameTiming {

Frame willApply()
{
    if (!ATrace_isEnabled())
        return {};

    Frame frame {++lastFrameNumber, monotonicTime()};
    ATrace_beginAsyncSection(inFlightSectionName, static_cast<int32_t>(frame.number));
    return frame;
}

void didComplete(const Frame& frame, ASurfaceTransactionStats* stats, ASurfaceControl* surfaceControl)
{
    if (!frame.number)
        return;

    ATrace_endAsyncSection(inFlightSectionName, static_cast<int32_t>(frame.number));

    static std::once_flag waiterStarted;
    std::call_once(waiterStarted, [] { std::thread(waitForPresentFences).detach(); });

    {
        std::lock_guard lock(queueLock);
        completedFrames.push_back({frame, ASurfaceTransactionStats_getAcquireTime(stats, surfaceControl),
            ASurfaceTransactionStats_getLatchTime(stats), ASurfaceTransactionStats_getPresentFenceFd(stats)});
    }
    queueCondition.notify_one();
}

} // namespace WPEFrameTiming
