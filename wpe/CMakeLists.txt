cmake_minimum_required(VERSION 3.4.1)

find_library(android-lib android)
find_library(log-lib log)

add_library(gio-2.0 SHARED IMPORTED)
set_target_properties(gio-2.0
        PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgio-2.0.so
        )

add_library(glib-2.0 SHARED IMPORTED)
set_target_properties(glib-2.0
        PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libglib-2.0.so
        )

add_library(gobject-2.0 SHARED IMPORTED)
set_target_properties(gobject-2.0
        PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgobject-2.0.so
        )

add_library(WPEWebKit-1.0 SHARED IMPORTED)
set_target_properties(WPEWebKit-1.0
        PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEWebKit-1.0_3.so
        )

add_library(libwpe-1.0 SHARED IMPORTED)
set_target_properties(libwpe-1.0
        PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libwpe-1.0.so
        )

add_library(WPEBackend-default SHARED IMPORTED)
set_target_properties(WPEBackend-default
        PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEBackend-default.so
        )

include_directories(
        imported/include/glib-2.0
        imported/lib/${ANDROID_ABI}/glib-2.0/include
        imported/include/libsoup-2.4
        imported/include
        imported/include/wpe
        imported/include/wpe-webkit
        src/main/glue/common
)

add_library(WPEBrowserGlue SHARED
        src/main/glue/common/jnihelper.h
        src/main/glue/browser/pageeventobserver.cpp
        src/main/glue/browser/entrypoints.cpp
        src/main/glue/browser/browser.cpp
        src/main/glue/browser/looperthread.cpp
        src/main/glue/browser/page.cpp
        src/main/glue/browser/inputmethodcontext.cpp
        )

target_link_libraries(WPEBrowserGlue
        ${android-lib}
        ${log-lib}
        gio-2.0
        glib-2.0
        gobject-2.0
        libwpe-1.0
        WPEBackend-default
        WPEWebKit-1.0
        )

add_library(WPENetworkProcessGlue SHARED
        src/main/glue/networkprocess/entrypoints.cpp
        )

target_link_libraries(WPENetworkProcessGlue
        ${log-lib}
        WPEWebKit-1.0
        )

add_library(WPEWebProcessGlue SHARED
        src/main/glue/webprocess/entrypoints.cpp
        )

target_link_libraries(WPEWebProcessGlue
        ${android-lib}
        ${log-lib}
        WPEBackend-default
        WPEWebKit-1.0
        )
