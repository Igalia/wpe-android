cmake_minimum_required(VERSION 3.4.1)

find_library(log-lib log)

add_library(glib-2.0_0 SHARED IMPORTED)
set_target_properties(glib-2.0_0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libglib-2.0_0.so
)

add_library(WPEWebKit_0 SHARED IMPORTED)
set_target_properties(WPEWebKit_0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEWebKit_0.so
)

include_directories(
    imported/include/glib-2.0
    imported/lib/${ANDROID_ABI}/glib-2.0/include
    imported/include/wpe-0.1
    imported/include/wpe-0.1/WPE
)

add_library(WPEUIProcessGlue SHARED
    src/main/WPEUIProcessGlue/glue.cpp
    src/main/WPEUIProcessGlue/wpeinstance.cpp
)

target_link_libraries(WPEUIProcessGlue
    ${log-lib}
    glib-2.0_0
    WPEWebKit_0
)