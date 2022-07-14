cmake_minimum_required(VERSION 3.18.1)
project("wpe-android")

if (NOT EXISTS "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}")
    set(IGNORE "${CMAKE_EXPORT_COMPILE_COMMANDS}${CMAKE_LIBRARY_OUTPUT_DIRECTORY}${CMAKE_RUNTIME_OUTPUT_DIRECTORY}")
    message(FATAL_ERROR "Native dependencies not installed for ${ANDROID_ABI}")
endif ()

find_library(android-lib android REQUIRED)
find_library(log-lib log REQUIRED)

set(WPE_ENABLE_PROCESS  TRUE)
add_definitions(-DWPE_ENABLE_PROCESS)

#################################################
# Imported external libraries
#################################################

add_library(gio-2.0 SHARED IMPORTED)
set_target_properties(gio-2.0 PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgio-2.0.so"
        INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include/glib-2.0")

add_library(glib-2.0 SHARED IMPORTED)
set_target_properties(glib-2.0 PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libglib-2.0.so"
        INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include/glib-2.0;${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/glib-2.0/include")

add_library(gobject-2.0 SHARED IMPORTED)
set_target_properties(gobject-2.0 PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgobject-2.0.so"
        INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include/glib-2.0")

add_library(gmodule-2.0 SHARED IMPORTED)
set_target_properties(gmodule-2.0 PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgmodule-2.0.so")

add_library(WPEWebKit-1.0 SHARED IMPORTED)
set_target_properties(WPEWebKit-1.0 PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEWebKit-1.0_3.so"
        INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include/wpe-webkit;${CMAKE_SOURCE_DIR}/imported/include/libsoup-2.4")

add_library(libwpe-1.0 SHARED IMPORTED)
set_target_properties(libwpe-1.0 PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libwpe-1.0.so"
        INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include/wpe")

add_library(WPEBackend-default SHARED IMPORTED)
set_target_properties(WPEBackend-default PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEBackend-default.so"
        INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include")

#################################################
# Built libraries
#################################################

# libWPECommonGlue
add_library(WPECommonGlue SHARED
        common/environment.cpp
        common/jnihelper.cpp
        common/logging.cpp
        common/service.cpp)
target_include_directories(WPECommonGlue INTERFACE common)
target_link_libraries(WPECommonGlue
        ${android-lib}
        ${log-lib})

# libWPEBrowserGlue
add_library(WPEBrowserGlue SHARED
        browser/pageeventobserver.cpp
        browser/entrypoints.cpp
        browser/browser.cpp
        browser/looperthread.cpp
        browser/messagepump.cpp
        browser/page.cpp
        browser/inputmethodcontext.cpp
        browser/renderer_asurfacetransaction.cpp
        browser/renderer_fallback.cpp)
target_link_libraries(WPEBrowserGlue
        EGL
        GLESv2
        gio-2.0
        glib-2.0
        gobject-2.0
        libwpe-1.0
        WPEBackend-default
        WPEWebKit-1.0
        WPECommonGlue)

# libWPENetworkProcessGlue
add_library(WPENetworkProcessGlue SHARED
        networkprocess/entrypoints.cpp)
target_link_libraries(WPENetworkProcessGlue
        WPEWebKit-1.0
        WPECommonGlue)

# libWPEWebProcessGlue
add_library(WPEWebProcessGlue SHARED
        webprocess/entrypoints.cpp)
target_link_libraries(WPEWebProcessGlue
        gio-2.0
        glib-2.0
        gmodule-2.0
        WPEBackend-default
        WPEWebKit-1.0
        WPECommonGlue)
