package com.github.highcumontoa.batterysafetymonitorjava.model;

import java.util.List;

/** 一批次上报的遥测采样。 */
public class SampleBatch {
    private String batchId;
    private String packId;
    private ProtocolVersion protocolVersion;
    private List<TelemetrySample> samples;

    public SampleBatch() {
    }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getPackId() { return packId; }
    public void setPackId(String packId) { this.packId = packId; }
    public ProtocolVersion getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(ProtocolVersion protocolVersion) { this.protocolVersion = protocolVersion; }
    public List<TelemetrySample> getSamples() { return samples; }
    public void setSamples(List<TelemetrySample> samples) { this.samples = samples; }
}
