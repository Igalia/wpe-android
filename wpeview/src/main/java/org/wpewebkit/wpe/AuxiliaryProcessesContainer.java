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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        for (PriorityHold hold : process.takePriorityHolds()) {
            priorityHolds.remove(hold.id);
            unbindService.accept(hold);
        }

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
     * @return An identifier for the hold, or 0 when the process is not registered or not ranked.
     */
    public long acquirePriority(long pid, boolean foreground) {
        AuxiliaryProcess process = pidToProcessMap.get(pid);
        if (process == null || process.getProcessType() != WPEProcessType.WebProcess)
            return 0;

        PriorityHold hold = new PriorityHold(++lastPriorityId, pid);
        int flags = Context.BIND_AUTO_CREATE | (foreground ? Context.BIND_IMPORTANT : Context.BIND_NOT_PERCEPTIBLE);
        if (!serviceBinder.bindService(process.getService(), hold, flags)) {
            // A failed binding is unbound too, as Context.bindService() asks.
            Log.w(LOGTAG, "Cannot bind a priority hold for process " + pid);
            unbindService.accept(hold);
            return 0;
        }
        process.addPriorityHold(hold);
        priorityHolds.put(hold.id, hold);
        return hold.id;
    }

    /** Releases a hold of acquirePriority(). One whose process was unregistered meanwhile is already released. */
    public void releasePriority(long priorityId) {
        PriorityHold hold = priorityHolds.remove(priorityId);
        if (hold == null)
            return;

        AuxiliaryProcess process = pidToProcessMap.get(hold.pid);
        if (process != null)
            process.removePriorityHold(hold);
        unbindService.accept(hold);
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
        final long id;
        final long pid;

        PriorityHold(long id, long pid) {
            this.id = id;
            this.pid = pid;
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
        private final List<PriorityHold> priorityHolds = new ArrayList<>();

        public AuxiliaryProcess(int processSlot, @NonNull WPEServiceConnection connection, @NonNull Intent service) {
            this.processSlot = processSlot;
            serviceConnection = connection;
            this.service = service;
        }

        public int getProcessSlot() { return processSlot; }

        public @NonNull WPEServiceConnection getServiceConnection() { return serviceConnection; }

        public WPEProcessType getProcessType() { return serviceConnection.getProcessType(); }

        public @NonNull Intent getService() { return service; }

        void addPriorityHold(@NonNull PriorityHold hold) { priorityHolds.add(hold); }

        void removePriorityHold(@NonNull PriorityHold hold) { priorityHolds.remove(hold); }

        @NonNull
        List<PriorityHold> takePriorityHolds() {
            List<PriorityHold> holds = new ArrayList<>(priorityHolds);
            priorityHolds.clear();
            return holds;
        }
    }
}
