package com.github.highcumontoa.batterysafetymonitorjava.model;

/** 单条采样的受理结论：接受或带原因的拒绝。 */
public record SampleVerdict(boolean accepted, RejectionReason reason, String detail) {

    public static SampleVerdict ok() {
        return new SampleVerdict(true, null, null);
    }

    public static SampleVerdict rejected(RejectionReason reason, String detail) {
        return new SampleVerdict(false, reason, detail);
    }
}
