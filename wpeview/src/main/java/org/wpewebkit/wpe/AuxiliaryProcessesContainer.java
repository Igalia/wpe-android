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

import android.content.ServiceConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.wpewebkit.wpe.services.WPEServiceConnection;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

final class AuxiliaryProcessesContainer {
    private static final int MAX_AUX_PROCESSES = org.wpewebkit.wpe.services.WPEServices.MAX_AUX_PROCESSES;

    private final AuxiliaryProcess[][] processes =
        new AuxiliaryProcess[WPEProcessType.values().length][MAX_AUX_PROCESSES];
    private final Map<Long, AuxiliaryProcess> pidToProcessMap = new HashMap<>();
    private final int[] firstAvailableSlot = new int[WPEProcessType.values().length];
    private final Consumer<ServiceConnection> unbindService;

    AuxiliaryProcessesContainer(@NonNull Consumer<ServiceConnection> unbindService) {
        this.unbindService = unbindService;
    }

    public int getFirstAvailableSlot(@NonNull WPEProcessType processType) {
        return firstAvailableSlot[processType.getValue()];
    }

    public void register(long pid, @NonNull WPEServiceConnection connection) {
        int typeIdx = connection.getProcessType().getValue();
        int slot = firstAvailableSlot[typeIdx];
        if (slot >= MAX_AUX_PROCESSES)
            throw new IllegalStateException("Limit exceeded spawning a new auxiliary process for " +
                                            connection.getProcessType().name());

        assert (processes[typeIdx][slot] == null);
        processes[typeIdx][slot] = new AuxiliaryProcess(slot, connection);
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

        process.getServiceConnection().dispose();
        if (process.getServiceConnection().isConnected())
            unbind(process);
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

    private static final class AuxiliaryProcess {
        private final int processSlot;
        private final WPEServiceConnection serviceConnection;

        public AuxiliaryProcess(int processSlot, @NonNull WPEServiceConnection connection) {
            this.processSlot = processSlot;
            serviceConnection = connection;
        }

        public int getProcessSlot() { return processSlot; }

        public @NonNull WPEServiceConnection getServiceConnection() { return serviceConnection; }

        public WPEProcessType getProcessType() { return serviceConnection.getProcessType(); }
    }
}
