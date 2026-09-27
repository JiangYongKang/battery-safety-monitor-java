package com.github.highcumontoa.batterysafetymonitorjava.model;

/** 一次对外告警事件。 */
public record AlertEvent(String alertId,
                         String packId,
                         String faultKey,
                         AlertLevel level,
                         DisposalAction action,
                         String triggerBasis,
                         long triggeredAtMillis) {
}
