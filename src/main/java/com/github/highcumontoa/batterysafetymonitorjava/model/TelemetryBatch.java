package com.github.highcumontoa.batterysafetymonitorjava.model;

import java.util.List;

/**
 * 一个批次上报：某电池包、某协议版本下的一批采样。
 *
 * @param batchId 批次唯一标识（用于重复上报幂等）
 * @param packId  电池包标识
 * @param protocolVersion 协议版本（v1 / v2）
 * @param sentAtMs 批次发送时间戳（乱序判定基准）
 * @param samples 采样列表
 */
public record TelemetryBatch(
        String batchId,
        String packId,
        String protocolVersion,
        long sentAtMs,
        List<TelemetrySample> samples
) {
}
