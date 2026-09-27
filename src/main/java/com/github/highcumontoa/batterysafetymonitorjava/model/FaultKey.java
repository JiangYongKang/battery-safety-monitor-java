package com.github.highcumontoa.batterysafetymonitorjava.model;

/**
 * 故障类型，每一种都绑定明确的阈值与持续时间规则。
 */
public enum FaultKey {
    TEMP_WARN("温度偏高", AlarmLevel.WARN),
    TEMP_CRIT("温度严重过高", AlarmLevel.CRITICAL),
    OVERVOLTAGE_WARN("过压预警", AlarmLevel.WARN),
    OVERVOLTAGE_CRIT("严重过压", AlarmLevel.CRITICAL),
    UNDERVOLTAGE_WARN("欠压预警", AlarmLevel.WARN),
    UNDERVOLTAGE_CRIT("严重欠压", AlarmLevel.CRITICAL),
    OVERCURRENT_WARN("过流预警", AlarmLevel.WARN),
    OVERCURRENT_CRIT("严重过流", AlarmLevel.CRITICAL),
    INSULATION_WARN("绝缘偏低", AlarmLevel.WARN),
    INSULATION_CRIT("绝缘严重不足", AlarmLevel.CRITICAL),
    THERMAL_RUNAWAY("热失控风险", AlarmLevel.CRITICAL);

    private final String display;
    private final AlarmLevel level;

    FaultKey(String display, AlarmLevel level) {
        this.display = display;
        this.level = level;
    }

    public String display() {
        return display;
    }

    public AlarmLevel level() {
        return level;
    }
}
