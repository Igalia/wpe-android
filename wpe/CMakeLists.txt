cmake_minimum_required(VERSION 3.4.1)

find_library(android-lib android)
find_library(log-lib log)

add_library(c++_shared SHARED IMPORTED)
set_target_properties(c++_shared
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libc++_shared.so
)

add_library(cairo SHARED IMPORTED)
set_target_properties(cairo
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libcairo.so
)

add_library(cairo_2 SHARED IMPORTED)
set_target_properties(cairo_2
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libcairo_2.so
)

add_library(epoxy SHARED IMPORTED)
set_target_properties(epoxy
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libepoxy.so
)

add_library(ffi_6 SHARED IMPORTED)
set_target_properties(ffi_6
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libffi_6.so
)

add_library(fontconfig SHARED IMPORTED)
set_target_properties(fontconfig
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libfontconfig.so
)

add_library(freetype SHARED IMPORTED)
set_target_properties(freetype
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libfreetype.so
)

add_library(gcrypt SHARED IMPORTED)
set_target_properties(gcrypt
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgcrypt.so
)

add_library(gio-2.0 SHARED IMPORTED)
set_target_properties(gio-2.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgio-2.0.so
)

add_library(giognutls SHARED IMPORTED)
set_target_properties(giognutls
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/gio-modules/${ANDROID_ABI}/libgiognutls.so
)

add_library(glib-2.0 SHARED IMPORTED)
set_target_properties(glib-2.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libglib-2.0.so
)

add_library(gmodule-2.0 SHARED IMPORTED)
set_target_properties(gmodule-2.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgmodule-2.0.so
)

add_library(gmp SHARED IMPORTED)
set_target_properties(gmp
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgmp.so
)

add_library(gnustl_shared SHARED IMPORTED)
set_target_properties(gnustl_shared
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgnustl_shared.so
)

add_library(gnutls SHARED IMPORTED)
set_target_properties(gnutls
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgnutls.so
)

add_library(gobject-2.0 SHARED IMPORTED)
set_target_properties(gobject-2.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgobject-2.0.so
)

add_library(gpg-error SHARED IMPORTED)
set_target_properties(gpg-error
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgpg-error.so
)

add_library(gstapp-1.0 SHARED IMPORTED)
set_target_properties(gstapp-1.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgstapp-1.0.so
)

add_library(gstaudio-1.0 SHARED IMPORTED)
set_target_properties(gstaudio-1.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgstaudio-1.0.so
)

add_library(gstbase-1.0 SHARED IMPORTED)
set_target_properties(gstbase-1.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgstbase-1.0.so
)

add_library(gstfft-1.0 SHARED IMPORTED)
set_target_properties(gstfft-1.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgstfft-1.0.so
)

add_library(gstpbutils-1.0 SHARED IMPORTED)
set_target_properties(gstpbutils-1.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgstpbutils-1.0.so
)

add_library(gstreamer-1.0 SHARED IMPORTED)
set_target_properties(gstreamer-1.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgstreamer-1.0.so
)

add_library(gsttag-1.0 SHARED IMPORTED)
set_target_properties(gsttag-1.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgsttag-1.0.so
)

add_library(gstvideo-1.0 SHARED IMPORTED)
set_target_properties(gstvideo-1.0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libgstvideo-1.0.so
)

add_library(harfbuzz-icu_0 SHARED IMPORTED)
set_target_properties(harfbuzz-icu_0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libharfbuzz-icu_0.so
)

add_library(harfbuzz_0 SHARED IMPORTED)
set_target_properties(harfbuzz_0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libharfbuzz_0.so
)

add_library(hogweed_4 SHARED IMPORTED)
set_target_properties(hogweed_4
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libhogweed_4.so
)

add_library(iconv SHARED IMPORTED)
set_target_properties(iconv
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libiconv.so
)

add_library(icudata_58 SHARED IMPORTED)
set_target_properties(icudata_58
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libicudata_58.so
)

add_library(icui18n_58 SHARED IMPORTED)
set_target_properties(icui18n_58
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libicui18n_58.so
)

add_library(icuuc_58 SHARED IMPORTED)
set_target_properties(icuuc_58
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libicuuc_58.so
)

add_library(icudata_59 SHARED IMPORTED)
set_target_properties(icudata_59
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libicudata_59.so
)

add_library(icui18n_59 SHARED IMPORTED)
set_target_properties(icui18n_59
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libicui18n_59.so
)

add_library(icuuc_59 SHARED IMPORTED)
set_target_properties(icuuc_59
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libicuuc_59.so
)

add_library(intl SHARED IMPORTED)
set_target_properties(intl
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libintl.so
)

add_library(jpeg SHARED IMPORTED)
set_target_properties(jpeg
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libjpeg.so
)

add_library(nettle_6 SHARED IMPORTED)
set_target_properties(nettle_6
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libnettle_6.so
)

add_library(pcre SHARED IMPORTED)
set_target_properties(pcre
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libpcre.so
)

add_library(pixman-1 SHARED IMPORTED)
set_target_properties(pixman-1
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libpixman-1.so
)

add_library(png16 SHARED IMPORTED)
set_target_properties(png16
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libpng16.so
)

add_library(soup-2.4 SHARED IMPORTED)
set_target_properties(soup-2.4
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libsoup-2.4.so
)

add_library(sqlite3 SHARED IMPORTED)
set_target_properties(sqlite3
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libsqlite3.so
)

add_library(tasn1_6 SHARED IMPORTED)
set_target_properties(tasn1_6
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libtasn1_6.so
)

add_library(webp_7 SHARED IMPORTED)
set_target_properties(webp_7
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libwebp_7.so
)

add_library(xml2 SHARED IMPORTED)
set_target_properties(xml2
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libxml2.so
)

add_library(xslt SHARED IMPORTED)
set_target_properties(xslt
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libxslt.so
)

add_library(webpdemux_2 SHARED IMPORTED)
set_target_properties(webpdemux_2
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libwebpdemux_2.so
)

add_library(WPEWebKit_0 SHARED IMPORTED)
set_target_properties(WPEWebKit_0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEWebKit_0.so
)

add_library(WPEWebKit_1 SHARED IMPORTED)
set_target_properties(WPEWebKit_1
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEWebKit_1.so
)

add_library(WPEBackend_0 SHARED IMPORTED)
set_target_properties(WPEBackend_0
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/imported/lib/${ANDROID_ABI}/libWPEBackend_0.so
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
    ffi_6
    fontconfig
    freetype
    gcrypt
    gio-2.0
    glib-2.0
    gmodule-2.0
    gmp
    gnutls
    gobject-2.0
    gpg-error
    gstapp-1.0
    gstaudio-1.0
    gstbase-1.0
    gstfft-1.0
    gstpbutils-1.0
    gstreamer-1.0
    gsttag-1.0
    gstvideo-1.0
    harfbuzz-icu_0
    harfbuzz_0
    hogweed_4
    iconv
    intl
    jpeg
    nettle_6
    pcre
    pixman-1
    png16
    soup-2.4
    sqlite3
    tasn1_6
    webp_7
    xml2
    xslt
    WPEBackend_0
    WPEBackend-default
    WPEWebKit_1
)

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_link_libraries(WPEUIProcessGlue
        cairo_2
        gnustl_shared
        icudata_58
        icui18n_58
        icuuc_58
        WPEWebKit_0
    )
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_libraries(WPEUIProcessGlue
        c++_shared
        cairo
        epoxy
        icudata_59
        icui18n_59
        icuuc_59
        webpdemux_2
    )
endif()

add_library(WPEStorageProcessGlue SHARED
    src/main/WPEStorageProcessGlue/entrypoints.cpp
)

target_link_libraries(WPEStorageProcessGlue
    ${log-lib}
    WPEWebKit_1
)

add_library(WPENetworkProcessGlue SHARED
    src/main/WPENetworkProcessGlue/entrypoints.cpp
)

target_link_libraries(WPENetworkProcessGlue
    ${log-lib}
    WPEWebKit_1
)

add_library(WPEWebProcessGlue SHARED
    src/main/WPEWebProcessGlue/entrypoints.cpp
)

target_link_libraries(WPEWebProcessGlue
    ${android-lib}
    ${log-lib}
    WPEBackend-default
    WPEWebKit_1
)
