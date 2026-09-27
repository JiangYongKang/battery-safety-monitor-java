package com.github.highcumontoa.batterysafetymonitorjava.model;

/** 采样被拒绝的可区分原因。 */
public enum RejectionReason {
    /** 通用必填字段缺失（packId / 时间戳 / 序号等）。 */
    MISSING_REQUIRED_FIELD,
    /** 协议版本要求的必填项不满足。 */
    PROTOCOL_REQUIRED_FIELD_MISSING,
    /** 数值超出物理量程。 */
    VALUE_OUT_OF_RANGE,
    /** 同一包内序号重复上报。 */
    DUPLICATE_SAMPLE
}
