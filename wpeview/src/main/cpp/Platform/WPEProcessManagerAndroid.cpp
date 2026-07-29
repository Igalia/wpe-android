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

#include "WPEProcessManagerAndroid.h"

#include "Environment.h"
#include "Logging.h"
#include "WKRuntime.h"

#include <unistd.h>

struct _WPEProcessManagerAndroid {
    WPEProcessManager parentInstance;
};

G_DEFINE_FINAL_TYPE(WPEProcessManagerAndroid, wpe_process_manager_android, WPE_TYPE_PROCESS_MANAGER)

static guint64 wpeProcessManagerAndroidLaunch(WPEProcessManager*, WPEProcessLaunchOptions* options, GError** error)
{
    auto wpeProcessType = wpe_process_launch_options_get_process_type(options);
    auto processId = wpe_process_launch_options_get_process_id(options);
    auto ipcSocketFD = wpe_process_launch_options_get_ipc_socket_fd(options);

    Logging::logDebug("WPEProcessManagerAndroid::launch(type=%d, pid=%" G_GUINT64_FORMAT ", fd=%d) [tid %d]",
        wpeProcessType, processId, ipcSocketFD, gettid());

    auto processType = ProcessType::TypesCount;
    if (wpeProcessType == WPE_PROCESS_TYPE_WEB) {
        processType = ProcessType::WebProcess;
        Logging::logVerbose("Launching WebProcess");
    } else if (wpeProcessType == WPE_PROCESS_TYPE_NETWORK) {
        processType = ProcessType::NetworkProcess;
        Logging::logVerbose("Launching NetworkProcess");
    }

    if ((processType < ProcessType::FirstType) || (processType >= ProcessType::TypesCount)) {
        g_set_error(error, WPE_PROCESS_MANAGER_ERROR, WPE_PROCESS_MANAGER_ERROR_LAUNCH_FAILED,
            "Cannot launch process (invalid process type: %d)", static_cast<int>(wpeProcessType));
        return 0;
    }

    if (!wkRuntimeLaunchProcess(static_cast<int64_t>(processId), static_cast<int>(processType), ipcSocketFD)) {
        g_set_error(error, WPE_PROCESS_MANAGER_ERROR, WPE_PROCESS_MANAGER_ERROR_LAUNCH_FAILED,
            "Failed to launch process (type: %d)", static_cast<int>(processType));
        return 0;
    }

    return processId;
}

static void wpeProcessManagerAndroidTerminate(WPEProcessManager*, guint64 pid)
{
    Logging::logDebug("WPEProcessManagerAndroid::terminate(%" G_GUINT64_FORMAT ") [tid %d]", pid, gettid());
    wkRuntimeTerminateProcess(static_cast<int64_t>(pid));
}

static void wpe_process_manager_android_init(WPEProcessManagerAndroid*)
{
}

static void wpe_process_manager_android_class_init(WPEProcessManagerAndroidClass* klass)
{
    auto* managerClass = WPE_PROCESS_MANAGER_CLASS(klass);
    managerClass->launch = wpeProcessManagerAndroidLaunch;
    managerClass->terminate = wpeProcessManagerAndroidTerminate;
}

WPEProcessManager* wpe_process_manager_android_new(void)
{
    return WPE_PROCESS_MANAGER(g_object_new(WPE_TYPE_PROCESS_MANAGER_ANDROID, nullptr));
}
