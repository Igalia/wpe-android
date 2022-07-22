package com.wpe.wpe;

import androidx.annotation.NonNull;

public enum ProcessType {
    WebProcess(0),
    NetworkProcess(1);

    private final int value;

    ProcessType(int value) { this.value = value; }

    @NonNull
    public static ProcessType fromValue(int value) {
        for (ProcessType entry : ProcessType.values()) {
            if (entry.getValue() == value)
                return entry;
        }

        throw new IllegalArgumentException("No available process type for value: " + value);
    }

    public int getValue() { return value; }
}
