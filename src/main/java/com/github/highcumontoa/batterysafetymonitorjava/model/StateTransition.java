package com.github.highcumontoa.batterysafetymonitorjava.model;

/**
 * 状态机迁移记录（可回溯时间线）。
 */
public record StateTransition(
        long timestampMs,
        HealthState from,
        HealthState to,
        String reason
) {
}
