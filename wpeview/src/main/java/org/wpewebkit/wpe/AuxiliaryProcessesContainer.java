/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.services.WPEServiceConnection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class AuxiliaryProcessesContainer {
    private static final String LOGTAG = "AuxiliaryProcesses";
    private static final int MAX_AUX_PROCESSES = org.wpewebkit.wpe.services.WPEServices.MAX_AUX_PROCESSES;

    /** Binds a service, as Context.bindService() does. */
    interface ServiceBinder {
        boolean bindService(@NonNull Intent service, @NonNull ServiceConnection connection, int flags);
    }

    private final AuxiliaryProcess[][] processes =
        new AuxiliaryProcess[WPEProcessType.values().length][MAX_AUX_PROCESSES];
    private final Map<Long, AuxiliaryProcess> pidToProcessMap = new HashMap<>();
    private final int[] firstAvailableSlot = new int[WPEProcessType.values().length];
    private final ServiceBinder serviceBinder;
    private final Consumer<ServiceConnection> unbindService;
    // The priorities WebKit holds on processes, by identifier (see acquirePriority()).
    private final Map<Long, PriorityHold> priorityHolds = new HashMap<>();
    private long lastPriorityId = 0;
    // The web processes to keep at the priority of the app if it leaves the front: those WebKit held in the foreground
    // when an activity last paused or the app last came back to the front, and those it held in the foreground since
    // (see onActivityPaused()).
    private final Set<Long> processesToKeepWhileAway = new HashSet<>();
    // Whether the app is away, with none of its activities started (see onAppLeftFront()).
    private boolean appAway = false;

    AuxiliaryProcessesContainer(@NonNull ServiceBinder serviceBinder,
                                @NonNull Consumer<ServiceConnection> unbindService) {
        this.serviceBinder = serviceBinder;
        this.unbindService = unbindService;
    }

    public int getFirstAvailableSlot(@NonNull WPEProcessType processType) {
        return firstAvailableSlot[processType.getValue()];
    }

    /**
     * The binding flags of the service of a process for its whole life. Android ranks a process by the strongest
     * binding to its service. A web process is ranked by the priorities WebKit holds on it (see acquirePriority()):
     * this binding waives the priority of the app, so that Android treats the process as cached, and ends it before
     * others when memory runs low, while WebKit holds none. The network process serves every page, so it keeps the
     * priority of the app.
     */
    public static int getBindingFlags(@NonNull WPEProcessType processType) {
        if (processType == WPEProcessType.WebProcess)
            return Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY;
        return Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
    }

    /**
     * @param service The intent the service of the process was bound with, which priority holds bind again.
     */
    public void register(long pid, @NonNull WPEServiceConnection connection, @NonNull Intent service) {
        int typeIdx = connection.getProcessType().getValue();
        int slot = firstAvailableSlot[typeIdx];
        if (slot >= MAX_AUX_PROCESSES)
            throw new IllegalStateException("Limit exceeded spawning a new auxiliary process for " +
                                            connection.getProcessType().name());

        assert (processes[typeIdx][slot] == null);
        processes[typeIdx][slot] = new AuxiliaryProcess(slot, connection, service);
        pidToProcessMap.put(pid, processes[typeIdx][slot]);

        while (++firstAvailableSlot[typeIdx] < MAX_AUX_PROCESSES) {
            if (processes[typeIdx][firstAvailableSlot[typeIdx]] == null)
                break;
        }
    }

    // Unbinds the service of a process. Its slot stays taken until the process of the service has ended (see
    // onProcessGone()), as binding the same service before would reach the process that is ending, and Android would
    // only start a new one when it restarts the service. A service that has not connected yet stays bound until it
    // connects (see onDisposedServiceConnected()): Android may have made it already, and unbinding it would end its
    // process, whose end can only be followed once the service connected.
    public void unregister(long pid) {
        AuxiliaryProcess process = pidToProcessMap.remove(pid);
        if (process == null)
            return;

        // Each hold binds the service with BIND_AUTO_CREATE, so one left behind would start the service again.
        priorityHolds.values().removeIf(hold -> {
            if (hold.pid != pid)
                return false;
            unbindService.accept(hold);
            return true;
        });
        if (process.appAwayHold != null)
            unbindService.accept(process.appAwayHold);
        processesToKeepWhileAway.remove(pid);

        process.getServiceConnection().dispose();
        if (process.getServiceConnection().isConnected())
            unbind(process);
    }

    /**
     * Keeps a registered web process at a priority until releasePriority(), with a binding of its own: Android ranks
     * the process by the strongest binding to its service, so holds may overlap.
     *
     * @param foreground Whether the user sees or hears the process, or the app waits on it. Otherwise it has work to
     * finish that nothing on screen depends on.
     * @return An identifier for the hold, or 0 when the process is not registered, not ranked, or cannot be bound.
     */
    public long acquirePriority(long pid, boolean foreground) {
        AuxiliaryProcess process = pidToProcessMap.get(pid);
        if (process == null || process.getProcessType() != WPEProcessType.WebProcess)
            return 0;

        PriorityHold hold = new PriorityHold(++lastPriorityId, pid, foreground);
        if (!bindPriorityHold(process.getService(), hold))
            return 0;
        priorityHolds.put(hold.id, hold);
        if (foreground)
            onForegroundHold(pid, process);
        return hold.id;
    }

    // Called once the hold is bound, so that a process whose hold of onAppLeftFront() is released keeps its priority.
    private void onForegroundHold(long pid, @NonNull AuxiliaryProcess process) {
        processesToKeepWhileAway.add(pid);
        if (appAway)
            return;
        // WebKit ranks the pages that the app shows again (see onAppBackInFront()).
        if (!process.keptWhenTheAppLeft)
            releaseAppAwayHolds();
        else if (process.appAwayHold != null)
            releaseAppAwayHold(process);
    }

    /** Releases a hold of acquirePriority(). One whose process was unregistered meanwhile is already released. */
    public void releasePriority(long priorityId) {
        PriorityHold hold = priorityHolds.remove(priorityId);
        if (hold != null)
            unbindService.accept(hold);
    }

    private boolean bindPriorityHold(@NonNull Intent service, @NonNull PriorityHold hold) {
        int flags =
            Context.BIND_AUTO_CREATE | (hold.foreground ? Context.BIND_IMPORTANT : Context.BIND_NOT_PERCEPTIBLE);
        if (!serviceBinder.bindService(service, hold, flags)) {
            // A failed binding is unbound too, as Context.bindService() asks.
            Log.w(LOGTAG, "Cannot bind a priority hold for process " + hold.pid);
            unbindService.accept(hold);
            return false;
        }
        return true;
    }

    /**
     * Notes the web processes that WebKit holds in the foreground when an activity of the app pauses, and those it
     * holds in the foreground after that, to keep them at the priority of the app if it leaves the front (see
     * onAppLeftFront()). Android pauses an activity before it hides its window, so this notes the page the activity
     * shows, whose hold WebKit may release before the activity stops, and a page shown while the activity is paused,
     * as after a load in a new process while a window of another app covers the activity.
     *
     * The note is for the whole app, as WPEView cannot tell which process shows the page of a view. It also takes the
     * pages of other activities, and the processes WebKit holds in the foreground for other reasons: one that plays
     * sound, one that is starting or loads a page, one that runs a script for the app, or a page hidden less than a
     * second before, as WebKit keeps a released hold for that long.
     */
    public void onActivityPaused() { noteProcessesHeldInTheForeground(); }

    private void noteProcessesHeldInTheForeground() {
        processesToKeepWhileAway.clear();
        for (PriorityHold hold : priorityHolds.values()) {
            if (hold.foreground)
                processesToKeepWhileAway.add(hold.pid);
        }
    }

    /**
     * Keeps the web processes noted by onActivityPaused() at the priority of the app while the app is away, with none
     * of its activities started. One of them runs the page the app showed: WebKit releases its hold once the page is
     * hidden, and the page frees memory as a hidden page does, but Android could otherwise end its process before the
     * app, and the user would come back to the page loading again. Others may play sound, and stay at the priority of
     * the app while it is away too. The processes of pages hidden before keep no priority, so Android still ends them
     * first, and one that kept this priority from a time the app left before loses it.
     *
     * @param activityFinishing Whether the activity that left is finishing, as when the user leaves with Back. Its
     * pages close, or no activity shows them any more, so no process keeps the priority of the app: the process of a
     * closed page may stay in the process cache of WebKit.
     */
    public void onAppLeftFront(boolean activityFinishing) {
        appAway = true;
        if (activityFinishing)
            processesToKeepWhileAway.clear();
        for (Map.Entry<Long, AuxiliaryProcess> entry : pidToProcessMap.entrySet()) {
            AuxiliaryProcess process = entry.getValue();
            process.keptWhenTheAppLeft = processesToKeepWhileAway.contains(entry.getKey());
            if (process.keptWhenTheAppLeft && process.appAwayHold == null) {
                PriorityHold hold = new PriorityHold(0, entry.getKey(), /* foreground */ true);
                if (bindPriorityHold(process.getService(), hold))
                    process.appAwayHold = hold;
            } else if (!process.keptWhenTheAppLeft && process.appAwayHold != null) {
                releaseAppAwayHold(process);
            }
        }
    }

    /**
     * Releases the holds of onAppLeftFront() once WebKit ranks the pages that the app shows again. Android shows the
     * window of an activity after the activity starts, and WebKit then holds the process of its page in the
     * foreground. So a process kept while the app was away loses its hold when WebKit holds it in the foreground: at
     * once if WebKit already does, as for a page that plays sound, or else later. The holds that are left go when
     * WebKit holds in the foreground a process that was not kept, as when the app comes back to another page, or else
     * when the app leaves the front again.
     *
     * Until an activity pauses, this also notes the processes for the next time the app leaves the front, as
     * onActivityPaused() does: an activity that starts may stop again without having resumed.
     */
    public void onAppBackInFront() {
        appAway = false;
        noteProcessesHeldInTheForeground();
        for (PriorityHold hold : priorityHolds.values()) {
            AuxiliaryProcess process = pidToProcessMap.get(hold.pid);
            if (hold.foreground && process != null && process.appAwayHold != null)
                releaseAppAwayHold(process);
        }
    }

    private void releaseAppAwayHold(@NonNull AuxiliaryProcess process) {
        unbindService.accept(process.appAwayHold);
        process.appAwayHold = null;
    }

    private void releaseAppAwayHolds() {
        for (AuxiliaryProcess process : pidToProcessMap.values()) {
            if (process.appAwayHold != null)
                releaseAppAwayHold(process);
        }
    }

    // Package-private: read by the tests.
    int getAppAwayHoldCount() {
        int count = 0;
        for (AuxiliaryProcess process : pidToProcessMap.values()) {
            if (process.appAwayHold != null)
                count++;
        }
        return count;
    }

    public void onDisposedServiceConnected(@NonNull WPEServiceConnection connection) {
        AuxiliaryProcess process = findUnregisteredProcess(connection);
        if (process != null)
            unbind(process);
    }

    public void onProcessGone(@NonNull WPEServiceConnection connection) {
        // A process still registered is unregistered when its service disconnects, which releases its slot.
        AuxiliaryProcess process = findUnregisteredProcess(connection);
        if (process != null)
            releaseSlot(process);
    }

    private void unbind(@NonNull AuxiliaryProcess process) {
        WPEServiceConnection connection = process.getServiceConnection();
        unbindService.accept(connection);
        if (!connection.mayHaveProcess())
            releaseSlot(process);
    }

    // The process of a connection that was unregistered and still holds its slot.
    private @Nullable AuxiliaryProcess findUnregisteredProcess(@NonNull WPEServiceConnection connection) {
        for (AuxiliaryProcess process : processes[connection.getProcessType().getValue()]) {
            if (process != null && process.getServiceConnection() == connection &&
                pidToProcessMap.get(connection.getPid()) != process)
                return process;
        }
        return null;
    }

    private void releaseSlot(@NonNull AuxiliaryProcess process) {
        int typeIdx = process.getProcessType().getValue();
        processes[typeIdx][process.getProcessSlot()] = null;
        firstAvailableSlot[typeIdx] = Math.min(process.getProcessSlot(), firstAvailableSlot[typeIdx]);
    }

    // A binding that only ranks the process of a service: the service is connected through WPEServiceConnection.
    private static final class PriorityHold implements ServiceConnection {
        // The identifier WebKit releases the hold with, or 0 for the hold of onAppLeftFront().
        final long id;
        final long pid;
        final boolean foreground;

        PriorityHold(long id, long pid, boolean foreground) {
            this.id = id;
            this.pid = pid;
            this.foreground = foreground;
        }

        @Override
        public void onServiceConnected(@NonNull ComponentName name, @NonNull IBinder service) {}

        @Override
        public void onServiceDisconnected(@NonNull ComponentName name) {}
    }

    private static final class AuxiliaryProcess {
        private final int processSlot;
        private final WPEServiceConnection serviceConnection;
        private final Intent service;
        // The hold of onAppLeftFront(), or null (see onAppBackInFront()).
        @Nullable
        PriorityHold appAwayHold;
        // Whether onAppLeftFront() kept the process at the priority of the app when the app last left the front.
        boolean keptWhenTheAppLeft = false;

        public AuxiliaryProcess(int processSlot, @NonNull WPEServiceConnection connection, @NonNull Intent service) {
            this.processSlot = processSlot;
            serviceConnection = connection;
            this.service = service;
        }

        public int getProcessSlot() { return processSlot; }

        public @NonNull WPEServiceConnection getServiceConnection() { return serviceConnection; }

        public WPEProcessType getProcessType() { return serviceConnection.getProcessType(); }

        public @NonNull Intent getService() { return service; }
    }
}
