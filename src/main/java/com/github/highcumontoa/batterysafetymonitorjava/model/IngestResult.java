package com.github.highcumontoa.batterysafetymonitorjava.model;

import java.util.List;

/** 一个批次的接入结果：逐条结论 + 汇总。 */
public record IngestResult(String batchId,
                           String packId,
                           int acceptedCount,
                           int rejectedCount,
                           List<RejectedSample> rejections) {

    /** 被拒绝的采样及其原因。 */
    public record RejectedSample(Long sequenceNumber, RejectionReason reason, String detail) {
    }
}
