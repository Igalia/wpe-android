#include "hacks.h"

#include "logging.h"
#include "view-backend.h"

#include <android/native_window.h>

ANativeWindow* s_hack_providedWindow = nullptr;

extern "C" {

__attribute__((visibility("default")))
void libwpe_android_provideNativeWindow(ANativeWindow* nativeWindow)
{
    ALOGV("libwpe_android_provideNativeWindow() nativeWindow %p size (%d,%d)",
        nativeWindow,
        ANativeWindow_getWidth(nativeWindow), ANativeWindow_getHeight(nativeWindow));
    s_hack_providedWindow = nativeWindow;
}

}
