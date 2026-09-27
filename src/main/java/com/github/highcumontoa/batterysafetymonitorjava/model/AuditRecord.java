package com.github.highcumontoa.batterysafetymonitorjava.model;

/** 处置审计记录：触发依据与状态时间线。 */
public record AuditRecord(long atMillis,
                          String packId,
                          String eventType,
                          String detail) {
}
