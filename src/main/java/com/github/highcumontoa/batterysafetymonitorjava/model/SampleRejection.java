package com.github.highcumontoa.batterysafetymonitorjava.model;

/**
 * 单条采样被拒绝的记录（不静默丢弃）。
 */
public record SampleRejection(
        int seq,
        RejectReason reason,
        String detail
) {
}
