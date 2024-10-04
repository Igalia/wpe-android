/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

#include "Logging.h"

#include <cstdio>
#include <fcntl.h>
#include <pthread.h>
#include <unistd.h>

static constexpr size_t BUFFER_SIZE = 8192;

bool Logging::pipeStdoutToLogcat() noexcept
{
    static bool s_alreadyInitialized = false;
    if (s_alreadyInitialized)
        return true;

    // Make stdout line-buffered and stderr unbuffered.
    (void)setvbuf(stdout, nullptr, _IOLBF, 0);
    (void)setvbuf(stderr, nullptr, _IONBF, 0);

    // Create the pipe and redirect stdout and stderr to the pipe write end.
    static int s_pfd[2] = {0};
    if (s_pfd[0] == 0 || s_pfd[1] == 0) {
        if (pipe2(s_pfd, O_CLOEXEC) == -1)
            return false;

        dup2(s_pfd[1], STDOUT_FILENO);
        dup2(s_pfd[1], STDERR_FILENO);
    }

    // Spawn the logging thread
    pthread_t loggingThread = 0;
    if (pthread_create(
            &loggingThread, nullptr,
            +[](void* userData) -> void* {
                ssize_t readSize = 0;
                char buf[BUFFER_SIZE] = {0};
                int* pfd = reinterpret_cast<int*>(userData);

                while ((readSize = read(pfd[0], buf, sizeof(buf) - 1)) > 0) {
                    if (buf[readSize - 1] == '\n')
                        --readSize;

                    buf[readSize] = '\0';
                    logDebug(buf);
                }

                close(pfd[0]);
                close(pfd[1]);

                return nullptr;
            },
            reinterpret_cast<void*>(s_pfd))
        != 0)
        return false;

    pthread_detach(loggingThread);
    s_alreadyInitialized = true;
    return true;
}
