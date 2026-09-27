package com.github.highcumontoa.batterysafetymonitorjava.model;

import java.util.List;

/**
 * 一次评估的结论。
 *
 * @param stale 后到旧数据是否被丢弃（true 表示本次未产生新结论）
 */
public record AssessmentResult(
        String packId,
        long evaluatedAtMs,
        long latestSampleTs,
        HealthState state,
        int healthScore,
        boolean stale,
        List<FaultSnapshot> activeFaults,
        List<AlarmEvent> newAlarms,
        List<ActionResult> newActions
) {
}
