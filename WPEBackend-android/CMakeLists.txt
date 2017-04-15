cmake_minimum_required(VERSION 3.0)

set(CMAKE_MODULE_PATH "${CMAKE_SOURCE_DIR}/cmake")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")

find_package(PkgConfig)
pkg_check_modules(WPE wpe REQUIRED)

find_package(GLIB 2.40.0 REQUIRED COMPONENTS gio gobject gthread gmodule)

set(WPE_ANDROID_INCLUDE_DIRECTORIES
    ${GLIB_INCLUDE_DIRS}
    ${WPE_INCLUDE_DIRS}
    "include"
)

set(WPE_ANDROID_LIBRARIES
    ${GLIB_LIBRARIES}
    ${GLIB_GIO_LIBRARIES}
    ${WPE_LIBRARIES}
    android
)

set(WPE_ANDROID_SOURCES
    src/android.cpp
    src/hacks.cpp
    src/ipc.cpp
    src/renderer-backend-egl.cpp
    src/renderer-host.cpp
    src/view-backend.cpp
    src/view-backend-exportable.cpp
)

add_library(WPEBackend-android SHARED ${WPE_ANDROID_SOURCES})
target_include_directories(WPEBackend-android PRIVATE ${WPE_ANDROID_INCLUDE_DIRECTORIES})
target_link_libraries(WPEBackend-android ${WPE_ANDROID_LIBRARIES})
