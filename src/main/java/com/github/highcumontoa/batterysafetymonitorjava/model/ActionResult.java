package com.github.highcumontoa.batterysafetymonitorjava.model;

import java.util.Map;

/**
 * 一次处置动作的审计记录。
 */
public record ActionResult(
        String actionId,
        String packId,
        AlarmActionType action,
        long executedAtMs,
        String triggerEvidence,
        Map<String, String> context
) {
}
