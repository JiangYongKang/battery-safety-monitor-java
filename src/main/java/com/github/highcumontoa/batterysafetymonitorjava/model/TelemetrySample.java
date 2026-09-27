package com.github.highcumontoa.batterysafetymonitorjava.model;

/**
 * 单条电池包遥测采样。
 * 数值字段使用包装类型以区分“缺失”与“零值”。
 */
public class TelemetrySample {
    private String packId;
    private Long sequenceNumber;
    private Long timestampMillis;
    private Double voltage;
    private Double current;
    private Double temperature;
    private Boolean insulationOk;

    public TelemetrySample() {
    }

    public String getPackId() { return packId; }
    public void setPackId(String packId) { this.packId = packId; }
    public Long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(Long sequenceNumber) { this.sequenceNumber = sequenceNumber; }
    public Long getTimestampMillis() { return timestampMillis; }
    public void setTimestampMillis(Long timestampMillis) { this.timestampMillis = timestampMillis; }
    public Double getVoltage() { return voltage; }
    public void setVoltage(Double voltage) { this.voltage = voltage; }
    public Double getCurrent() { return current; }
    public void setCurrent(Double current) { this.current = current; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Boolean getInsulationOk() { return insulationOk; }
    public void setInsulationOk(Boolean insulationOk) { this.insulationOk = insulationOk; }
}
