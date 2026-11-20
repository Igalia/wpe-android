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
import android.content.Context;
import android.content.Intent;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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

    // The services the container bound, their flags and the processes they run, in the order it bound them.
    final List<ServiceConnection> boundServices = new ArrayList<>();
    final List<Integer> boundFlags = new ArrayList<>();
    final List<Long> boundPids = new ArrayList<>();
    boolean bindingSucceeds = true;
    final List<ServiceConnection> unboundServices = new ArrayList<>();
    // How many services the container had bound when it unbound each of unboundServices.
    final List<Integer> boundBeforeUnbinding = new ArrayList<>();
    final AuxiliaryProcessesContainer container =
        new AuxiliaryProcessesContainer(this::bindService, this::unbindService);
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

    private boolean bindService(@NonNull Intent service, @NonNull ServiceConnection connection, int flags) {
        boundServices.add(connection);
        boundFlags.add(flags);
        boundPids.add(service.getLongExtra("pid", 0));
        return bindingSucceeds;
    }

    private void unbindService(@NonNull ServiceConnection connection) {
        unboundServices.add(connection);
        boundBeforeUnbinding.add(boundServices.size());
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
        return launchProcess(pid, WPEProcessType.WebProcess);
    }

    private @NonNull WPEServiceConnection launchProcess(long pid, @NonNull WPEProcessType type) throws IOException {
        ParcelFileDescriptor[] socketPair = ParcelFileDescriptor.createSocketPair();
        sockets.add(socketPair[1]);
        AtomicReference<WPEServiceConnection> connection = new AtomicReference<>();
        onMainThread(() -> {
            connection.set(new WPEServiceConnection(pid, type, socketPair[0], listener));
            container.register(pid, connection.get(), new Intent().putExtra("pid", pid));
        });
        return connection.get();
    }

    private long acquirePriority(long pid, boolean foreground) {
        AtomicReference<Long> priorityId = new AtomicReference<>();
        onMainThread(() -> priorityId.set(container.acquirePriority(pid, foreground)));
        return priorityId.get();
    }

    private int appAwayHolds() {
        AtomicInteger count = new AtomicInteger();
        onMainThread(() -> count.set(container.getAppAwayHoldCount()));
        return count.get();
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

    @Test
    public void testPriorityHoldsBindTheServiceUntilReleased() throws IOException {
        FakeService service = new FakeService();
        connect(launchProcess(1), service);

        long foreground = acquirePriority(1, true);
        long background = acquirePriority(1, false);
        assertNotEquals(0, foreground);
        assertNotEquals(0, background);
        assertNotEquals(foreground, background);
        assertEquals(List.of(Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                             Context.BIND_AUTO_CREATE | Context.BIND_NOT_PERCEPTIBLE),
                     boundFlags);

        onMainThread(() -> container.releasePriority(foreground));
        assertEquals(List.of(boundServices.get(0)), unboundServices);
        onMainThread(() -> container.releasePriority(foreground));
        assertEquals("A hold is released once", 1, unboundServices.size());
    }

    /** A hold left bound would start the service of an ended process again, with no socket, and keep its slot. */
    @Test
    public void testPriorityHoldsAreReleasedWithTheirProcess() throws IOException {
        FakeService service = new FakeService();
        WPEServiceConnection connection = launchProcess(1);
        connect(connection, service);
        long foreground = acquirePriority(1, true);
        long background = acquirePriority(1, false);

        onMainThread(() -> listener.onServiceDisconnected(connection));
        assertEquals("Both holds are unbound, in any order, before the service",
                     Set.of(boundServices.get(0), boundServices.get(1)), new HashSet<>(unboundServices.subList(0, 2)));
        assertEquals(List.of(connection), unboundServices.subList(2, unboundServices.size()));

        onMainThread(() -> {
            container.releasePriority(foreground);
            container.releasePriority(background);
        });
        assertEquals("WebKit releases its holds after the process ended", 3, unboundServices.size());
        assertEquals(0, acquirePriority(1, true));
    }

    @Test
    public void testOnlyRegisteredWebProcessesAreRanked() throws IOException {
        connect(launchProcess(1, WPEProcessType.NetworkProcess), new FakeService());

        assertEquals(0, acquirePriority(1, true));
        assertEquals(0, acquirePriority(2, true));
        assertEquals(List.of(), boundServices);
    }

    @Test
    public void testFailedPriorityBindingIsUnbound() throws IOException {
        connect(launchProcess(1), new FakeService());

        bindingSucceeds = false;
        assertEquals(0, acquirePriority(1, true));
        assertEquals(boundServices, unboundServices);
    }

    /**
     * The page the app showed when its activity paused keeps the priority of the app while the app is away, so that
     * Android does not rank it below the app. Android may hide the window, and WebKit release its hold, before the
     * activity stops. Pages hidden before keep no priority.
     */
    @Test
    public void testPageShownAtPauseKeepsThePriorityOfTheAppWhileItIsAway() throws IOException {
        connect(launchProcess(1), new FakeService());
        connect(launchProcess(2), new FakeService());
        connect(launchProcess(3), new FakeService());
        long shown = acquirePriority(1, true);
        acquirePriority(2, false);

        onMainThread(container::onActivityPaused);
        onMainThread(() -> container.releasePriority(shown));
        assertEquals(List.of(boundServices.get(0)), unboundServices);
        onMainThread(() -> container.onAppLeftFront(false));
        onMainThread(() -> container.onAppLeftFront(false));
        assertEquals("Process 1 is held once more, and only process 1", List.of(1L, 2L, 1L), boundPids);
        assertEquals(Integer.valueOf(Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT), boundFlags.get(2));
        assertEquals(1, unboundServices.size());
    }

    /**
     * A page may be shown after the activity paused and before it stops, as after a load in a new process while a
     * window of another app covers the activity. It keeps the priority of the app too.
     */
    @Test
    public void testPageShownWhileTheActivityIsPausedKeepsThePriorityOfTheApp() throws IOException {
        connect(launchProcess(1), new FakeService());
        connect(launchProcess(2), new FakeService());
        connect(launchProcess(3), new FakeService());
        long hidden = acquirePriority(1, true);
        onMainThread(container::onActivityPaused);
        onMainThread(() -> container.releasePriority(hidden));
        onMainThread(container::onActivityPaused);

        long shown = acquirePriority(2, true);
        acquirePriority(3, false);
        onMainThread(() -> container.releasePriority(shown));
        onMainThread(() -> container.onAppLeftFront(false));
        assertEquals("Process 2 is held once more, and only process 2", List.of(1L, 2L, 3L, 2L), boundPids);
    }

    /**
     * When the app comes back, the process of the page it showed keeps the priority of the app until WebKit holds it
     * in the foreground again, once Android shows the window of the activity. Another process that the app showed
     * when it left keeps it until WebKit holds a process in the foreground that has none, as when the app shows
     * another page.
     */
    @Test
    public void testAppAwayHoldIsReleasedOnceWebKitHoldsItsProcessAgain() throws IOException {
        connect(launchProcess(1), new FakeService());
        connect(launchProcess(2), new FakeService());
        connect(launchProcess(3), new FakeService());
        long shown = acquirePriority(1, true);
        long other = acquirePriority(2, true);
        onMainThread(container::onActivityPaused);
        onMainThread(() -> {
            container.releasePriority(shown);
            container.releasePriority(other);
            container.onAppLeftFront(false);
        });
        assertEquals(2, appAwayHolds());
        ServiceConnection appAwayOfShown = boundServices.get(boundPids.lastIndexOf(1L));
        ServiceConnection appAwayOfOther = boundServices.get(boundPids.lastIndexOf(2L));

        onMainThread(container::onAppBackInFront);
        assertEquals("WebKit does not hold the pages yet", 2, appAwayHolds());

        acquirePriority(1, true);
        assertEquals(1, appAwayHolds());
        assertEquals(appAwayOfShown, unboundServices.get(unboundServices.size() - 1));
        assertEquals("The process is held again before its hold is released", boundServices.size(),
                     (int)boundBeforeUnbinding.get(boundBeforeUnbinding.size() - 1));
        acquirePriority(1, true);
        assertEquals("Another hold on a process kept while the app was away releases no other", 1, appAwayHolds());

        acquirePriority(3, true);
        assertEquals(0, appAwayHolds());
        assertEquals(appAwayOfOther, unboundServices.get(unboundServices.size() - 1));
    }

    /**
     * An activity that starts may stop again without having resumed, as one that is shown behind a window of another
     * app. A page shown since the app came back keeps the priority of the app when it leaves again.
     */
    @Test
    public void testPageShownBeforeAnActivityResumedKeepsThePriorityOfTheApp() throws IOException {
        connect(launchProcess(1), new FakeService());
        connect(launchProcess(2), new FakeService());
        long shown = acquirePriority(1, true);
        onMainThread(container::onActivityPaused);
        onMainThread(() -> {
            container.releasePriority(shown);
            container.onAppLeftFront(false);
            container.onAppBackInFront();
        });

        long shownSinceBack = acquirePriority(2, true);
        onMainThread(() -> {
            container.releasePriority(shownSinceBack);
            container.onAppLeftFront(false);
        });
        assertEquals(1, appAwayHolds());
        assertEquals(List.of(1L, 1L, 2L, 2L), boundPids);
    }

    /**
     * A process that WebKit holds in the foreground while the app is away, as one that plays sound, keeps its rank by
     * that hold when the app comes back. A page that starts to play sound while the app is away releases no hold.
     */
    @Test
    public void testAppAwayHoldOfAProcessWebKitHoldsIsReleasedWhenTheAppComesBack() throws IOException {
        connect(launchProcess(1), new FakeService());
        connect(launchProcess(2), new FakeService());
        connect(launchProcess(3), new FakeService());
        acquirePriority(1, true);
        long shown = acquirePriority(2, true);
        onMainThread(container::onActivityPaused);
        onMainThread(() -> {
            container.releasePriority(shown);
            container.onAppLeftFront(false);
        });
        assertEquals(2, appAwayHolds());

        acquirePriority(3, true);
        assertEquals(2, appAwayHolds());

        onMainThread(container::onAppBackInFront);
        assertEquals(1, appAwayHolds());
        assertEquals(boundServices.get(boundPids.lastIndexOf(1L)), unboundServices.get(unboundServices.size() - 1));
    }

    /**
     * When the user leaves with Back, the activity finishes and its pages close, so no process keeps the priority of
     * the app: the process of a closed page may stay in the process cache of WebKit. A process that kept it from a
     * time the app left before loses it.
     */
    @Test
    public void testNoProcessKeepsThePriorityOfTheAppWhenTheActivityThatLeftIsFinishing() throws IOException {
        connect(launchProcess(1), new FakeService());
        connect(launchProcess(2), new FakeService());
        long shown = acquirePriority(1, true);
        onMainThread(container::onActivityPaused);
        onMainThread(() -> {
            container.releasePriority(shown);
            container.onAppLeftFront(false);
        });
        acquirePriority(2, true);
        onMainThread(container::onAppBackInFront);
        assertEquals(1, appAwayHolds());

        onMainThread(container::onActivityPaused);
        onMainThread(() -> container.onAppLeftFront(true));
        assertEquals(0, appAwayHolds());
        assertEquals("No process is held once more", List.of(1L, 1L, 2L), boundPids);
    }

    /**
     * A process that kept the priority of the app from a time the app left before, and that the app did not show
     * since, loses it when the app leaves again.
     */
    @Test
    public void testProcessNotShownSinceTheAppCameBackLosesThePriorityOfTheApp() throws IOException {
        connect(launchProcess(1), new FakeService());
        long shown = acquirePriority(1, true);
        onMainThread(container::onActivityPaused);
        onMainThread(() -> {
            container.releasePriority(shown);
            container.onAppLeftFront(false);
            container.onAppBackInFront();
        });
        ServiceConnection appAway = boundServices.get(1);

        onMainThread(container::onActivityPaused);
        onMainThread(() -> container.onAppLeftFront(false));
        assertEquals(0, appAwayHolds());
        assertEquals(appAway, unboundServices.get(unboundServices.size() - 1));
    }

    @Test
    public void testHoldsWhileTheAppIsAwayAreReleasedWithTheirProcess() throws IOException {
        WPEServiceConnection connection = launchProcess(1);
        connect(connection, new FakeService());
        acquirePriority(1, true);
        onMainThread(container::onActivityPaused);
        onMainThread(() -> container.onAppLeftFront(false));
        ServiceConnection appAway = boundServices.get(1);

        onMainThread(() -> listener.onServiceDisconnected(connection));
        assertEquals(List.of(boundServices.get(0), appAway, connection), unboundServices);
        onMainThread(container::onAppBackInFront);
        assertEquals("The hold was released with its process", 3, unboundServices.size());
    }

    @Test
    public void testProcessEndedBeforeTheAppLeftIsNotHeld() throws IOException {
        WPEServiceConnection connection = launchProcess(1);
        connect(connection, new FakeService());
        acquirePriority(1, true);
        onMainThread(container::onActivityPaused);

        onMainThread(() -> listener.onServiceDisconnected(connection));
        onMainThread(() -> container.onAppLeftFront(false));
        assertEquals(1, boundServices.size());
    }
}
