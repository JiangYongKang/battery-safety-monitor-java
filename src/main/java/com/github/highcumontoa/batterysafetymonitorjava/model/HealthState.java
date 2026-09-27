package com.github.highcumontoa.batterysafetymonitorjava.model;

/**
 * 电池包健康状态机，level 单调递增表示严重程度升级。
 */
public enum HealthState {
    NORMAL(0),
    WARNING(1),
    CRITICAL(2),
    THERMAL_RUNAWAY(3);

    private final int level;

    HealthState(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }
}
