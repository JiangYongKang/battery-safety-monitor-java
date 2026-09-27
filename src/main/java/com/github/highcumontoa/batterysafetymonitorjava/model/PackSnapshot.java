package com.github.highcumontoa.batterysafetymonitorjava.model;

import java.util.List;

/**
 * 电池包整体状态快照。
 */
public record PackSnapshot(
        String packId,
        HealthState state,
        int healthScore,
        long lastAcceptedAtMs,
        long lastBatchSentAtMs,
        long lastEvaluatedSampleTs,
        List<FaultSnapshot> faults,
        List<StateTransition> timeline
) {
}
