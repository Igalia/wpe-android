/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "LocalNetwork.h"

#include "JNI/JNI.h"
#include "Logging.h"

#include <android/api-level.h>
#include <array>
#include <dlfcn.h>
#include <gio/gio.h>
#include <string>

namespace {

// Android 17, the first with local network protection.
constexpr int ANDROID_17_API_LEVEL = 37;

// android_getnetworkblockedreason() is in libandroid.so, but the NDK does not declare it yet, nor its reasons. Local
// network protection, ANDROID_NETWORK_BLOCKED_REASON_LNP, is 1.
using GetNetworkBlockedReasonFunction = int32_t (*)(int);
constexpr int32_t NETWORK_BLOCKED_REASON_LOCAL_NETWORK = 1;

// When to ask Android whether it blocks a connection, in milliseconds after the previous check. Android drops the first
// packet as the connection starts, and it can tell so at once, so the first check runs right after GLib starts the
// connection. The later ones cover the retries of the first packet, after one and three seconds.
constexpr std::array<guint, 4> CHECK_DELAYS_MS = {0, 100, 900, 2000};

class JavaCallbacks final : public JNI::Class {
public:
    JavaCallbacks()
        : JNI::Class("org/wpewebkit/wpe/services/WPEService", true)
        , m_onLocalNetworkBlocked(getStaticMethod<void(jstring)>("onLocalNetworkBlocked"))
    {
    }

    void onLocalNetworkBlocked(const std::string& host) const
    {
        if (!m_onLocalNetworkBlocked.invoke(static_cast<jstring>(JNI::String(host.c_str()))))
            Logging::logError("cannot deliver WPEService onLocalNetworkBlocked callback");
    }

private:
    const JNI::StaticMethod<void(jstring)> m_onLocalNetworkBlocked;
};

const JavaCallbacks* javaCallbacks = nullptr;
GetNetworkBlockedReasonFunction getNetworkBlockedReason = nullptr;

// A connection, while it is being checked. It is held weakly: GSocketClient does not close an attempt that fails, is
// cancelled or loses to another one, it drops its reference, and the socket closes as the connection goes away.
struct Connection {
    Connection(GSocketConnection* connection, std::string host)
        : host(std::move(host))
    {
        g_weak_ref_init(&weakConnection, connection);
    }

    ~Connection()
    {
        g_weak_ref_clear(&weakConnection);
    }

    Connection(const Connection&) = delete;
    Connection& operator=(const Connection&) = delete;
    Connection(Connection&&) = delete;
    Connection& operator=(Connection&&) = delete;

    GWeakRef weakConnection;
    std::string host;
    size_t nextCheck {0};
};

// Asks Android whether it blocks the connection. If it does, tells Java, then ends the connection, so that the load
// fails now and not after the kernel's connect timeout. Java hears of it first, so that the app knows the reason when
// the load fails.
bool checkBlocked(GSocket* socket, const std::string& host)
{
    if (getNetworkBlockedReason(g_socket_get_fd(socket)) != NETWORK_BLOCKED_REASON_LOCAL_NETWORK)
        return false;

    Logging::logDebug("LocalNetwork: Android blocked a connection to %s", host.c_str());
    javaCallbacks->onLocalNetworkBlocked(host);
    g_autoptr(GError) error = nullptr;
    if (!g_socket_shutdown(socket, TRUE, TRUE, &error))
        Logging::logError("LocalNetwork: cannot end a blocked connection: %s", error->message);
    return true;
}

void scheduleCheck(Connection* connection);

gboolean onCheckTimeout(gpointer data)
{
    auto* connection = static_cast<Connection*>(data);
    g_autoptr(GSocketConnection) socketConnection
        = static_cast<GSocketConnection*>(g_weak_ref_get(&connection->weakConnection));
    GSocket* socket = socketConnection ? g_socket_connection_get_socket(socketConnection) : nullptr;
    bool done = !socket || g_socket_is_closed(socket) || g_socket_is_connected(socket)
        || checkBlocked(socket, connection->host) || ++connection->nextCheck == CHECK_DELAYS_MS.size();
    if (done)
        delete connection;
    else
        scheduleCheck(connection);
    return G_SOURCE_REMOVE;
}

void scheduleCheck(Connection* connection)
{
    g_autoptr(GSource) source = g_timeout_source_new(CHECK_DELAYS_MS[connection->nextCheck]);
    g_source_set_callback(source, onCheckTimeout, connection, nullptr);
    g_source_attach(source, g_main_context_get_thread_default());
}

// The host name of the connection's destination, or failing that, the address it connects to.
std::string destinationHost(GSocketConnectable* connectable, GInetAddress* address)
{
    if (G_IS_NETWORK_ADDRESS(connectable))
        return g_network_address_get_hostname(G_NETWORK_ADDRESS(connectable));
    if (G_IS_NETWORK_SERVICE(connectable))
        return g_network_service_get_domain(G_NETWORK_SERVICE(connectable));
    g_autofree char* addressString = g_inet_address_to_string(address);
    return addressString;
}

// GSocketClient::event, for every GSocketClient. While the event is G_SOCKET_CLIENT_CONNECTING, the connection's remote
// address is the one it is about to connect to. Every connection but those to loopback is checked: Android decides what
// is on the local network, for example the addresses of the Wi-Fi network the device is on.
gboolean onSocketClientEvent(GSignalInvocationHint*, guint paramCount, const GValue* params, gpointer)
{
    if (paramCount < 4 || g_value_get_enum(&params[1]) != G_SOCKET_CLIENT_CONNECTING)
        return TRUE;

    auto* connectable = G_SOCKET_CONNECTABLE(g_value_get_object(&params[2]));
    auto* connection = g_value_get_object(&params[3]);
    if (!connectable || !G_IS_SOCKET_CONNECTION(connection))
        return TRUE;

    g_autoptr(GSocketAddress) remoteAddress
        = g_socket_connection_get_remote_address(G_SOCKET_CONNECTION(connection), nullptr);
    if (!G_IS_INET_SOCKET_ADDRESS(remoteAddress))
        return TRUE;

    GInetAddress* address = g_inet_socket_address_get_address(G_INET_SOCKET_ADDRESS(remoteAddress));
    if (g_inet_address_get_is_loopback(address))
        return TRUE;

    // GLib starts connecting right after this event: the first check runs then.
    scheduleCheck(new Connection(G_SOCKET_CONNECTION(connection), destinationHost(connectable, address)));
    return TRUE;
}

} // namespace

namespace LocalNetwork {
void watchConnections()
{
    if (android_get_device_api_level() < ANDROID_17_API_LEVEL)
        return;

    // Not unloaded: libandroid.so stays loaded in every app process anyway.
    if (void* libandroid = dlopen("libandroid.so", RTLD_NOW)) {
        getNetworkBlockedReason
            = reinterpret_cast<GetNetworkBlockedReasonFunction>(dlsym(libandroid, "android_getnetworkblockedreason"));
    }
    if (!getNetworkBlockedReason) {
        Logging::logError("LocalNetwork: android_getnetworkblockedreason not found, blocked connections will time out");
        return;
    }

    javaCallbacks = new JavaCallbacks();

    // The class must exist to look up its signal. It is never released, so the hook stays valid.
    g_type_class_ref(G_TYPE_SOCKET_CLIENT);
    guint eventSignal = g_signal_lookup("event", G_TYPE_SOCKET_CLIENT);
    g_signal_add_emission_hook(eventSignal, 0, onSocketClientEvent, nullptr, nullptr);
}
} // namespace LocalNetwork
