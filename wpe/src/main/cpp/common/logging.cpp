#include "logging.h"

#include <pthread.h>
#include <cstdio>
#include <unistd.h>
#include <cstdlib>

bool wpe::android::pipeStdoutToLogcat()
{
    // Make stdout line-buffered and stderr unbuffered
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);

    // Create the pipe and redirect stdout and stderr
    int pfd[2] = { 0 };
    pipe(pfd);
    dup2(pfd[1], 1);
    dup2(pfd[1], 2);

    // Spawn the logging thread
    pthread_t logging_thread = 0;
    if (pthread_create(&logging_thread, nullptr, +[](void* userData) -> void* {
        ssize_t rdsz = 0;
        char buf[128] = { 0 };
        while ((rdsz = read(reinterpret_cast<int*>(userData)[0], buf, sizeof(buf) - 1)) > 0) {
            if (buf[rdsz - 1] == '\n')
                --rdsz;

            buf[rdsz] = '\0';
            __android_log_write(ANDROID_LOG_DEBUG, "wpe-android", buf);
        }

        return nullptr;
    }, reinterpret_cast<void*>(pfd)) != 0)
        return false;

    pthread_detach(logging_thread);
    return true;
}

void wpe::android::enableGstDebug()
{
    setenv("GST_DEBUG", "3", 1);
}
