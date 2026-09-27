package com.github.highcumontoa.batterysafetymonitorjava.model;

/**
 * 采样/批次被拒绝的可区分原因。
 */
public enum RejectReason {
    PROTOCOL_VERSION_UNSUPPORTED,
    MISSING_REQUIRED_FIELD,
    OUT_OF_RANGE,
    DUPLICATE_BATCH,
    STALE_BATCH,
    DUPLICATE_SAMPLE,
    STALE_SAMPLE,
    EMPTY_BATCH,
    MALFORMED_PAYLOAD
}
