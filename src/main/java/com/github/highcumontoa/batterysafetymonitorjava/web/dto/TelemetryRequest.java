package com.github.highcumontoa.batterysafetymonitorjava.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 采样上报 HTTP 请求体（弱类型接收，便于对缺失字段给出可区分原因）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TelemetryRequest {
    public String batchId;
    public String packId;
    public String protocolVersion;
    public Long sentAtMs;
    public List<SampleDto> samples;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SampleDto {
        public Integer seq;
        public Long timestampMs;
        public Double voltageV;
        public Double currentA;
        public Double tempC;
        public Double insulationKOhm;
    }
}
