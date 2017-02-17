cmake_minimum_required(VERSION 3.0)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")

find_package(PkgConfig)
pkg_check_modules(WPE wpe REQUIRED)

set(WPE_ANDROID_INCLUDE_DIRECTORIES
    ${WPE_INCLUDE_DIRS}
)

set(WPE_ANDROID_LIBRARIES
    ${WPE_LIBRARIES}
)

set(WPE_ANDROID_SOURCES
    src/android.cpp
)

add_library(WPE-android SHARED ${WPE_ANDROID_SOURCES})
target_include_directories(WPE-android PRIVATE ${WPE_ANDROID_INCLUDE_DIRECTORIES})
target_link_libraries(WPE-android ${WPE_ANDROID_LIBRARIES})
