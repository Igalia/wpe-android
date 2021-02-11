cmake_minimum_required(VERSION 3.4.1)

find_library(android-lib android)
find_library(log-lib log)

add_library(glib-2.0 SHARED IMPORTED)
set_target_properties(glib-2.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libglib-2.0.so
)

add_library(WPEWebKit_1 SHARED IMPORTED)
set_target_properties(WPEWebKit_1
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEWebKit_1.0.so
)

add_library(WPEBackend_0 SHARED IMPORTED)
set_target_properties(WPEBackend_0
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
    imported/include/wpe-0.1
    imported/include/wpe-0.1/WPE
    src/main/CommonGlue
)

add_library(WPEUIProcessGlue SHARED
    src/main/WPEUIProcessGlue/entrypoints.cpp
    src/main/WPEUIProcessGlue/wpeuiprocessglue.cpp
)

target_link_libraries(WPEUIProcessGlue
    ${log-lib}
    glib-2.0
    libwpe-1.0
    WPEBackend-default
    WPEWebKit_1.0
)

add_library(WPEStorageProcessGlue SHARED
    src/main/WPEStorageProcessGlue/entrypoints.cpp
)

target_link_libraries(WPEStorageProcessGlue
    ${log-lib}
    WPEWebKit_1.0
)

add_library(WPENetworkProcessGlue SHARED
    src/main/WPENetworkProcessGlue/entrypoints.cpp
)

target_link_libraries(WPENetworkProcessGlue
    ${log-lib}
    WPEWebKit_1.0
)

add_library(WPEWebProcessGlue SHARED
    src/main/WPEWebProcessGlue/entrypoints.cpp
)

target_link_libraries(WPEWebProcessGlue
    ${android-lib}
    ${log-lib}
    WPEBackend-default
    WPEWebKit_1.0
)
