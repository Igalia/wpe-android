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

import androidx.annotation.NonNull;

import org.wpewebkit.wpe.services.WPEServiceConnection;

import java.util.HashMap;
import java.util.Map;

final class AuxiliaryProcessesContainer {
    private static final int MAX_AUX_PROCESSES = 40;

    private final AuxiliaryProcess[][] processes = new AuxiliaryProcess[ProcessType.values().length][MAX_AUX_PROCESSES];
    private final Map<Long, AuxiliaryProcess> pidToProcessMap = new HashMap<>();
    private final int[] firstAvailableSlot = new int[ProcessType.values().length];

    public int getFirstAvailableSlot(@NonNull ProcessType processType) {
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

    public void unregister(long pid) {
        AuxiliaryProcess process = pidToProcessMap.remove(pid);
        if (process == null)
            return;

        process.terminate();

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

        public ProcessType getProcessType() { return serviceConnection.getProcessType(); }

        public void terminate() { Browser.getInstance().getApplicationContext().unbindService(serviceConnection); }
    }
}
