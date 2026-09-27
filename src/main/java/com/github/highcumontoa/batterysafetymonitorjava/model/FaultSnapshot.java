package com.github.highcumontoa.batterysafetymonitorjava.model;

/**
 * 单个故障的当前快照。
 */
public record FaultSnapshot(
        FaultKey fault,
        boolean active,
        long firstViolationMs,
        long lastViolationMs,
        long clearedAtMs,
        String evidence
) {
}
