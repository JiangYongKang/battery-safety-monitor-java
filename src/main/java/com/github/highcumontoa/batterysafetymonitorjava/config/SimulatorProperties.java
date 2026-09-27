package com.github.highcumontoa.batterysafetymonitorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内置模拟遥测开关配置（仅用于本地演示，默认关闭）。
 */
@ConfigurationProperties(prefix = "battery.simulator")
public class SimulatorProperties {

    private boolean enabled = false;
    /** 批次间隔（毫秒，模拟时钟同样以该步长推进）。 */
    private long intervalMs = 2_000;
    /** 单批采样条数。 */
    private int samplesPerBatch = 3;
    /** 采样时间步长（毫秒）。 */
    private long sampleStepMs = 2_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    public int getSamplesPerBatch() { return samplesPerBatch; }
    public void setSamplesPerBatch(int samplesPerBatch) { this.samplesPerBatch = samplesPerBatch; }
    public long getSampleStepMs() { return sampleStepMs; }
    public void setSampleStepMs(long sampleStepMs) { this.sampleStepMs = sampleStepMs; }
}
