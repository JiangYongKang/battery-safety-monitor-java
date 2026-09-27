package com.github.highcumontoa.batterysafetymonitorjava.model;

/**
 * 一次外部告警事件（抑制窗口内每种故障只允许一次）。
 */
public record AlarmEvent(
        String eventId,
        String packId,
        FaultKey fault,
        AlarmLevel level,
        long triggeredAtMs,
        String evidence
) {
}
