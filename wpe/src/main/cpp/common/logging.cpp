#include "logging.h"

#include <pthread.h>
#include <cstdio>
#include <unistd.h>

bool wpe::android::pipeStdoutToLogcat()
{
    static bool s_alreadyInitialized = false;
    if (s_alreadyInitialized)
        return true;

    // Make stdout line-buffered and stderr unbuffered.
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);

    // Create the pipe and redirect stdout and stderr to the pipe write end.
    static int s_pfd[2] = { 0 };
    if (s_pfd[0] == 0 || s_pfd[1] == 0) {
        if (pipe(s_pfd) == -1)
            return false;

        dup2(s_pfd[1], STDOUT_FILENO);
        dup2(s_pfd[1], STDERR_FILENO);
    }

    // Spawn the logging thread
    pthread_t logging_thread = 0;
    if (pthread_create(&logging_thread, nullptr, +[](void* userData) -> void* {
        ssize_t readSize = 0;
        char buf[4096] = { 0 };
        int* pfd = reinterpret_cast<int*>(userData);

        while ((readSize = read(pfd[0], buf, sizeof(buf) - 1)) > 0) {
            if (buf[readSize - 1] == '\n')
                --readSize;

            buf[readSize] = '\0';
            __android_log_write(ANDROID_LOG_DEBUG, "wpe-android", buf);
        }

        close(pfd[0]);
        close(pfd[1]);

        return nullptr;
    }, reinterpret_cast<void*>(s_pfd)) != 0)
        return false;

    pthread_detach(logging_thread);
    s_alreadyInitialized = true;
    return true;
}
