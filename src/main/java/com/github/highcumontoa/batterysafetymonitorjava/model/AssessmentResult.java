package com.github.highcumontoa.batterysafetymonitorjava.model;

/** 一次健康度 / 热失控风险评估结论。 */
public record AssessmentResult(String packId,
                               HealthState state,
                               double healthScore,
                               double thermalRiskScore,
                               int windowSize,
                               String basis,
                               long assessedAtMillis) {
}
