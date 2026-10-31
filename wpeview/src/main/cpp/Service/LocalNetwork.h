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

#pragma once

// Android 17 lets an app reach the local network only with the ACCESS_LOCAL_NETWORK permission. Without it, Android
// drops the packets, so a TCP connection only ends with the kernel's connect timeout, minutes later. WebKit's network
// process connects through GLib's GSocketClient, which tells the address it connects to before connecting.
namespace LocalNetwork {
// Watches the connections of the network process, from the thread that runs it, before it starts. When Android blocks
// one, WPEService.onLocalNetworkBlocked() is told, so that the app can ask for the permission, and the connection is
// ended at once, so that the load fails with an error the app can tell from others. Does nothing before Android 17.
void watchConnections();
} // namespace LocalNetwork
