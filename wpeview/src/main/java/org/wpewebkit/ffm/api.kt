package org.wpewebkit.ffm

import com.v7878.foreign.FunctionDescriptor
import com.v7878.foreign.Linker
import com.v7878.foreign.MemoryLayout
import com.v7878.foreign.ValueLayout.ADDRESS
import com.v7878.foreign.ValueLayout.JAVA_DOUBLE
import com.v7878.foreign.ValueLayout.JAVA_INT

//
// TYPES
//
val CString = ADDRESS.withName("CString")

val GObject = ADDRESS.withName("GObject")

val gboolean = JAVA_INT.withName("gboolean")
const val TRUE = 1
const val FALSE = 0

fun fromGBoolean(value: Int): Boolean = value != FALSE
fun toGBoolean(value: Boolean): Int
{
    if (value)
        return TRUE
    return FALSE
}

val WebKitCookieManager = ADDRESS.withName("WebKitCookieManager")
val WebKitDownload = ADDRESS.withName("WebKitDownload")
val WebKitNetworkSession = ADDRESS.withName("WebKitNetworkSession")
val WebKitSettings = ADDRESS.withName("WebKitSettings")
val WebKitURIRequest = ADDRESS.withName("WebKitURIRequest")
val WebKitURIResponse = ADDRESS.withName("WebKitURIResponse")
val WebKitUserContentManager = ADDRESS.withName("WebKitUserContentManager")
val WebKitWebContext = ADDRESS.withName("WebKitWebContext")
val WebKitWebView = ADDRESS.withName("WebKitWebView")
val WebKitWebsiteDataManager = ADDRESS.withName("WebKitWebsiteDataManager")

//
// GObject
//
val g_object_ref by
    method(GObject, GObject)

val g_object_ref_sink by
    method(GObject, GObject)

val g_object_unref by
    voidMethod(GObject)

val g_object_is_floating by
    method(gboolean, GObject)

//
// Versioning
//
val webkit_get_major_version by method(JAVA_INT)
val webkit_get_minor_version by method(JAVA_INT)
val webkit_get_micro_version by method(JAVA_INT)

//
// WebKitDownload
//
val webkit_download_cancel by
    voidMethod(WebKitDownload)

val webkit_download_get_allow_overwrite by
    method(gboolean, WebKitDownload)
val webkit_download_set_allow_overwrite by
    voidMethod(WebKitDownload, gboolean)

val webkit_download_get_destination by
    method(CString, WebKitDownload)
val webkit_download_set_destination by
    voidMethod(WebKitDownload, CString)

val webkit_download_get_elapsed_time by
    method(JAVA_DOUBLE, WebKitDownload)

val webkit_download_get_estimated_progress by
    method(JAVA_DOUBLE, WebKitDownload)

val webkit_download_get_request by
    method(WebKitURIRequest, WebKitDownload)

val webkit_download_get_response by
    method(WebKitURIResponse, WebKitDownload)

val webkit_download_get_web_view by
    method(WebKitWebView, WebKitDownload)

//
// WebKitNetworkSession
//
val webkit_network_session_new by
    method(WebKitNetworkSession, CString, CString)

val webkit_network_session_new_ephemeral by
    method(WebKitNetworkSession)

val webkit_network_session_get_default by
    method(WebKitNetworkSession)

val webkit_network_session_download_uri by
    method(WebKitDownload, WebKitNetworkSession, CString)

val webkit_network_session_get_cookie_manager by
    method(WebKitCookieManager, WebKitNetworkSession)

val webkit_network_session_get_itp_enabled by
    method(gboolean, WebKitNetworkSession)
val webkit_network_session_set_itp_enabled by
    voidMethod(WebKitNetworkSession, gboolean)

val webkit_network_session_get_website_data_manager by
    method(WebKitWebsiteDataManager, WebKitNetworkSession)

val webkit_network_session_is_ephemeral by
    method(gboolean, WebKitNetworkSession)

val webkit_network_session_set_persistent_credential_storage_enabled by
    voidMethod(WebKitNetworkSession, gboolean)

//
// WebKitWebView
//
val webkit_web_view_new by
    method(WebKitWebView)

val webkit_web_view_can_go_back by
    method(gboolean, WebKitWebView)
val webkit_web_view_go_back by
    voidMethod(WebKitWebView)

val webkit_web_view_can_go_forward by
    method(gboolean, WebKitWebView)
val webkit_web_view_go_forward by
    voidMethod(WebKitWebView)

val webkit_web_view_can_show_mime_type by
    method(gboolean, WebKitWebView, CString)

val webkit_web_view_download_uri by
    method(WebKitDownload, WebKitWebView, CString)

val webkit_web_view_execute_editing_command by
    voidMethod(WebKitWebView, CString)

val webkit_web_view_get_context by
    method(WebKitWebContext, WebKitWebView)

val webkit_web_view_get_estimated_load_progress by
    method(JAVA_DOUBLE, WebKitWebView)

val webkit_web_view_get_network_session by
    method(WebKitNetworkSession, WebKitWebView)

val webkit_web_view_get_settings by
    method(WebKitSettings, WebKitWebView)

val webkit_web_view_get_title by
    method(CString, WebKitWebView)

val webkit_web_view_get_uri by
    method(CString, WebKitWebView)

val webkit_web_view_get_user_content_manager by
    method(WebKitUserContentManager, WebKitWebView)

val webkit_web_view_get_zoom_level by
    method(JAVA_DOUBLE, WebKitWebView)

val webkit_web_view_load_html by
    voidMethod(WebKitWebView, CString, CString)
val webkit_web_view_load_plain_text by
    voidMethod(WebKitWebView, CString)
val webkit_web_view_load_uri by
    voidMethod(WebKitWebView, CString)
val webkit_web_view_reload by
    voidMethod(WebKitWebView)
val webkit_web_view_reload_bypass_cache by
    voidMethod(WebKitWebView)
val webkit_web_view_stop_loading by
    voidMethod(WebKitWebView)
