package com.github.highcumontoa.batterysafetymonitorjava.model;

/** 电池包健康状态，ordinal 即严重度，状态迁移沿 ordinal 单调。 */
public enum HealthState {
    NORMAL,
    ATTENTION,
    WARNING,
    CRITICAL
}
