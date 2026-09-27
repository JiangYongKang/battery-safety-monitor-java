package com.github.highcumontoa.batterysafetymonitorjava.model;

/**
 * 单条采样（归一化后的内部模型）。
 *
 * @param seq        采样在批次内的序号（从 0 起）
 * @param timestampMs 采样时间戳（毫秒，模拟时钟）
 * @param voltageV   电压（V），必填
 * @param currentA   电流（A，必填）
 * @param tempC      温度（℃），必填
 * @param insulationKOhm 绝缘电阻（kΩ），协议 v2 必填
 */
public record TelemetrySample(
        int seq,
        long timestampMs,
        double voltageV,
        double currentA,
        double tempC,
        Double insulationKOhm
) {
}
