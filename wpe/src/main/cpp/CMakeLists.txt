cmake_minimum_required(VERSION 3.22.1)
project("wpe-android")

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}")
    set(IGNORE "${CMAKE_EXPORT_COMPILE_COMMANDS}${CMAKE_LIBRARY_OUTPUT_DIRECTORY}${CMAKE_RUNTIME_OUTPUT_DIRECTORY}")
    message(FATAL_ERROR "Native dependencies not installed for ${ANDROID_ABI}")
endif()

option(USE_CODE_TIDY "Use code static analysis on build" ON)
option(USE_CODE_WARNINGS_AS_ERRORS "Use code warnings as errors" ON)

if(USE_CODE_TIDY)
    find_program(
        CLANG_TIDY_EXE
        NAMES clang-tidy
        PATHS ${ANDROID_TOOLCHAIN_ROOT}/bin
        NO_DEFAULT_PATH
        DOC "Path to clang-tidy executable")
    if(CLANG_TIDY_EXE)
        message(STATUS "Using clang-tidy from ${CLANG_TIDY_EXE}")
    else()
        message(FATAL_ERROR "clang-tidy not found, please install tool and relaunch configuration")
    endif()
endif()

function(target_configure_quality TARGET_NAME)
    set_target_properties(${TARGET_NAME} PROPERTIES CXX_STANDARD 17 CXX_EXTENSIONS OFF)
    if(CLANG_TIDY_EXE)
        set_target_properties(${TARGET_NAME} PROPERTIES CXX_CLANG_TIDY "${CLANG_TIDY_EXE}")
    endif()

    if(USE_CODE_WARNINGS_AS_ERRORS)
        target_compile_options(${TARGET_NAME} PRIVATE -Wall -Wextra -Werror)
    endif()
endfunction()

find_library(android-lib android REQUIRED)
find_library(log-lib log REQUIRED)
find_library(sync-lib sync REQUIRED)

# ######################################################################################################################
# Imported external libraries
# ######################################################################################################################

add_library(gio-2.0 SHARED IMPORTED)
set_target_properties(
    gio-2.0 PROPERTIES IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgio-2.0.so"
                       INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include/glib-2.0")

add_library(glib-2.0 SHARED IMPORTED)
set_target_properties(
    glib-2.0
    PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libglib-2.0.so"
        INTERFACE_INCLUDE_DIRECTORIES
        "${CMAKE_SOURCE_DIR}/imported/include/glib-2.0;${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/glib-2.0/include"
)

add_library(gobject-2.0 SHARED IMPORTED)
set_target_properties(
    gobject-2.0 PROPERTIES IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgobject-2.0.so"
                           INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include/glib-2.0")

add_library(gmodule-2.0 SHARED IMPORTED)
set_target_properties(gmodule-2.0 PROPERTIES IMPORTED_LOCATION
                                             "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgmodule-2.0.so")

add_library(WPEWebKit-2.0 SHARED IMPORTED)
set_target_properties(
    WPEWebKit-2.0
    PROPERTIES IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEWebKit-2.0_1.so"
               INTERFACE_INCLUDE_DIRECTORIES
               "${CMAKE_SOURCE_DIR}/imported/include/wpe-webkit;${CMAKE_SOURCE_DIR}/imported/include/libsoup-3.0")

add_library(libwpe-1.0 SHARED IMPORTED)
set_target_properties(
    libwpe-1.0 PROPERTIES IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libwpe-1.0.so"
                          INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include/wpe")

add_library(WPEBackend SHARED IMPORTED)
set_target_properties(
    WPEBackend PROPERTIES IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEBackend-android.so"
                          INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include")

add_library(WPEWebDriver SHARED IMPORTED)
set_target_properties(WPEWebDriver PROPERTIES IMPORTED_LOCATION
                                              "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEWebDriver.so")

# ######################################################################################################################
# Built libraries
# ######################################################################################################################

# libWPEAndroidCommon
add_library(WPEAndroidCommon SHARED Common/Environment.cpp Common/Logging.cpp Common/JNI/JNIClass.cpp
                                    Common/JNI/JNIEnv.cpp Common/JNI/JNIString.cpp)
target_configure_quality(WPEAndroidCommon)
target_include_directories(WPEAndroidCommon INTERFACE Common)
target_link_libraries(WPEAndroidCommon ${android-lib} ${log-lib} ${sync-lib})

# libWPEAndroidBrowser
add_library(
    WPEAndroidBrowser SHARED
    Browser/Browser.cpp
    Browser/EntryPoint.cpp
    Browser/Fence.cpp
    Browser/InputMethodContext.cpp
    Browser/LooperThread.cpp
    Browser/MessagePump.cpp
    Browser/Page.cpp
    Browser/PageSettings.cpp
    Browser/RendererSurfaceControl.cpp
    Browser/SurfaceControl.cpp
    Browser/WKCallback.cpp
    Browser/WKCookieManager.cpp
    Browser/WKNetworkSession.cpp
    Browser/WKWebContext.cpp
    Browser/WKWebsiteDataManager.cpp)
target_configure_quality(WPEAndroidBrowser)
target_compile_definitions(WPEAndroidBrowser PRIVATE WPE_ENABLE_PROCESS)
target_link_libraries(
    WPEAndroidBrowser
    EGL
    GLESv2
    gio-2.0
    glib-2.0
    gobject-2.0
    libwpe-1.0
    WPEBackend
    WPEWebKit-2.0
    WPEAndroidCommon)

# libWPEAndroidService
add_library(WPEAndroidService SHARED Service/EntryPoint.cpp)
target_configure_quality(WPEAndroidService)
target_link_libraries(
    WPEAndroidService
    gio-2.0
    glib-2.0
    gmodule-2.0
    WPEBackend
    WPEWebKit-2.0
    WPEAndroidCommon
    WPEWebDriver)
