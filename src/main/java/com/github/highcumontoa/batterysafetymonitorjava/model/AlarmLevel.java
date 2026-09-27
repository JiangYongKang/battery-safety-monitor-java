package com.github.highcumontoa.batterysafetymonitorjava.model;

/**
 * 外部告警分级。
 */
public enum AlarmLevel {
    INFO(0),
    WARN(1),
    CRITICAL(2);

    private final int severity;

    AlarmLevel(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }
}
