cmake_minimum_required(VERSION 3.18.1)
project("wpe-android")

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}")
    set(IGNORE "${CMAKE_EXPORT_COMPILE_COMMANDS}${CMAKE_LIBRARY_OUTPUT_DIRECTORY}${CMAKE_RUNTIME_OUTPUT_DIRECTORY}")
    message(FATAL_ERROR "Native dependencies not installed for ${ANDROID_ABI}")
endif()

option(USE_CODE_TIDY "Use code static analysis on build" OFF)
option(USE_CODE_WARNINGS_AS_ERRORS "Use code warnings as errors" OFF)

if(USE_CODE_TIDY)
    find_program(
        CLANG_TIDY_EXE
        NAMES clang-tidy-14 clang-tidy
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

add_library(WPEWebKit-1.0 SHARED IMPORTED)
set_target_properties(
    WPEWebKit-1.0
    PROPERTIES IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEWebKit-1.0_3.so"
               INTERFACE_INCLUDE_DIRECTORIES
               "${CMAKE_SOURCE_DIR}/imported/include/wpe-webkit;${CMAKE_SOURCE_DIR}/imported/include/libsoup-2.4")

add_library(libwpe-1.0 SHARED IMPORTED)
set_target_properties(
    libwpe-1.0 PROPERTIES IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libwpe-1.0.so"
                          INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include/wpe")

add_library(WPEBackend-default SHARED IMPORTED)
set_target_properties(
    WPEBackend-default
    PROPERTIES IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEBackend-default.so"
               INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_SOURCE_DIR}/imported/include")

# ######################################################################################################################
# Built libraries
# ######################################################################################################################

# libWPECommonGlue
add_library(WPECommonGlue SHARED Common/Environment.cpp Common/JNIHelper.cpp Common/Logging.cpp Common/Service.cpp)
target_configure_quality(WPECommonGlue)
target_include_directories(WPECommonGlue INTERFACE Common)
target_link_libraries(WPECommonGlue ${android-lib} ${log-lib})

# libWPEBrowserGlue
add_library(
    WPEBrowserGlue SHARED
    Browser/PageEventObserver.cpp
    Browser/EntryPoints.cpp
    Browser/Browser.cpp
    Browser/LooperThread.cpp
    Browser/MessagePump.cpp
    Browser/Page.cpp
    Browser/InputMethodContext.cpp
    Browser/RendererASurfaceTransaction.cpp
    Browser/RendererFallback.cpp)
target_configure_quality(WPEBrowserGlue)
target_compile_definitions(WPEBrowserGlue PRIVATE WPE_ENABLE_PROCESS)
target_link_libraries(
    WPEBrowserGlue
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
add_library(WPENetworkProcessGlue SHARED NetworkProcess/EntryPoints.cpp)
target_configure_quality(WPENetworkProcessGlue)
target_link_libraries(WPENetworkProcessGlue WPEWebKit-1.0 WPECommonGlue)

# libWPEWebProcessGlue
add_library(WPEWebProcessGlue SHARED WebProcess/EntryPoints.cpp)
target_configure_quality(WPEWebProcessGlue)
target_link_libraries(
    WPEWebProcessGlue
    gio-2.0
    glib-2.0
    gmodule-2.0
    WPEBackend-default
    WPEWebKit-1.0
    WPECommonGlue)
