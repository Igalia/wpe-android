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

package org.wpewebkit.wpe;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wpewebkit.wpe.services.WPEServiceConnection;
import org.wpewebkit.wpe.services.WPEServiceConnectionListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

/**
 * Each slot of a process type is an Android service, which can only run a new process once the process it ran has
 * ended. The connections and the container live on the main thread, as in WPERuntime.
 */
@RunWith(AndroidJUnit4.class)
public class AuxiliaryProcessesContainerTest {
    // A service in the process of the test, which tells that its process ended when the test says.
    private static final class FakeService extends IWPEService.Stub {
        private IBinder.DeathRecipient deathRecipient;
        int connections = 0;

        @Override
        public int connect(Bundle args, IWPEServiceHost serviceHost) {
            connections++;
            return 0;
        }

        @Override
        public void linkToDeath(@NonNull IBinder.DeathRecipient recipient, int flags) {
            deathRecipient = recipient;
        }

        void endProcess() {
            deathRecipient.binderDied();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }

    final List<ServiceConnection> unboundServices = new ArrayList<>();
    final AuxiliaryProcessesContainer container = new AuxiliaryProcessesContainer(unboundServices::add);
    // Tells the container what WPERuntime tells it.
    final WPEServiceConnectionListener listener = new WPEServiceConnectionListener() {
        @Override
        public void onCleanExit(@NonNull WPEServiceConnection connection) {
            container.unregister(connection.getPid());
        }

        @Override
        public void onServiceDisconnected(@NonNull WPEServiceConnection connection) {
            container.unregister(connection.getPid());
        }

        @Override
        public void onDisposedServiceConnected(@NonNull WPEServiceConnection connection) {
            container.onDisposedServiceConnected(connection);
        }

        @Override
        public void onProcessGone(@NonNull WPEServiceConnection connection) {
            container.onProcessGone(connection);
        }
    };
    private final List<ParcelFileDescriptor> sockets = new ArrayList<>();

    @After
    public void tearDown() throws IOException {
        for (ParcelFileDescriptor socket : sockets)
            socket.close();
    }

    private static void onMainThread(@NonNull Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }

    private int firstAvailableSlot() {
        AtomicInteger slot = new AtomicInteger();
        onMainThread(() -> slot.set(container.getFirstAvailableSlot(WPEProcessType.WebProcess)));
        return slot.get();
    }

    private @NonNull WPEServiceConnection launchProcess(long pid) throws IOException {
        ParcelFileDescriptor[] socketPair = ParcelFileDescriptor.createSocketPair();
        sockets.add(socketPair[1]);
        AtomicReference<WPEServiceConnection> connection = new AtomicReference<>();
        onMainThread(() -> {
            connection.set(new WPEServiceConnection(pid, WPEProcessType.WebProcess, socketPair[0], listener));
            container.register(pid, connection.get());
        });
        return connection.get();
    }

    private static void connect(@NonNull WPEServiceConnection connection, @NonNull FakeService service) {
        ComponentName name = new ComponentName("org.wpewebkit.test", "FakeService");
        onMainThread(() -> connection.onServiceConnected(name, service));
    }

    @Test
    public void testSlotIsGivenAgainOnceItsProcessEnded() throws IOException {
        FakeService service = new FakeService();
        WPEServiceConnection connection = launchProcess(1);
        connect(connection, service);
        assertEquals(1, firstAvailableSlot());

        onMainThread(() -> listener.onCleanExit(connection));
        assertEquals(List.of(connection), unboundServices);
        assertEquals("The process of slot 0 still runs", 1, firstAvailableSlot());
        launchProcess(2);
        assertEquals(2, firstAvailableSlot());

        service.endProcess();
        assertEquals(0, firstAvailableSlot());
    }

    @Test
    public void testSlotIsGivenAgainWhenItsProcessEndedBeforeBeingUnregistered() throws IOException {
        FakeService service = new FakeService();
        WPEServiceConnection connection = launchProcess(1);
        connect(connection, service);

        service.endProcess();
        assertEquals(1, firstAvailableSlot());
        onMainThread(() -> listener.onServiceDisconnected(connection));
        assertEquals(0, firstAvailableSlot());
    }

    /**
     * Android may have made the service of a process already when WebKit ends the process, before the service
     * connected. Unbinding the service then would end its process, with no way to know when it ended.
     */
    @Test
    public void testSlotOfAServiceUnregisteredBeforeItConnectedIsGivenAgainOnceItsProcessEnded() throws IOException {
        FakeService service = new FakeService();
        WPEServiceConnection connection = launchProcess(1);
        onMainThread(() -> container.unregister(1));
        assertEquals("The service was unbound before it connected", List.of(), unboundServices);
        assertEquals(1, firstAvailableSlot());

        connect(connection, service);
        assertEquals(List.of(connection), unboundServices);
        assertEquals("The service was told to connect after it was unregistered", 0, service.connections);
        assertEquals("The process of slot 0 still runs", 1, firstAvailableSlot());

        service.endProcess();
        assertEquals(0, firstAvailableSlot());
    }

    @Test
    public void testEndedProcessDoesNotFreeTheSlotOfTheNextOne() throws IOException {
        FakeService service = new FakeService();
        WPEServiceConnection connection = launchProcess(1);
        connect(connection, service);
        onMainThread(() -> listener.onCleanExit(connection));
        service.endProcess();
        launchProcess(2);
        assertEquals(1, firstAvailableSlot());

        // Late news of the first process, which already ended, leaves the second one in its slot.
        service.endProcess();
        onMainThread(() -> {
            listener.onProcessGone(connection);
            listener.onServiceDisconnected(connection);
            listener.onCleanExit(connection);
        });
        assertEquals(1, firstAvailableSlot());
        assertEquals(1, unboundServices.size());
    }
}
