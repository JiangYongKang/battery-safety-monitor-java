package com.github.highcumontoa.batterysafetymonitorjava.model;

import java.util.List;

/**
 * 批次接入结果。
 */
public record IngestResult(
        String batchId,
        String packId,
        boolean accepted,
        int acceptedSamples,
        int rejectedSamples,
        List<SampleRejection> sampleRejections,
        RejectReason batchRejectReason,
        String message
) {
}
