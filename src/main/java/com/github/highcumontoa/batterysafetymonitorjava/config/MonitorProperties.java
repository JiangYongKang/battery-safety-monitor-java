package com.github.highcumontoa.batterysafetymonitorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 监控规则配置（阈值、窗口、抑制等），前缀 battery.monitor。
 */
@ConfigurationProperties(prefix = "battery.monitor")
public class MonitorProperties {

    private List<String> supportedProtocols = List.of("v1", "v2");
    private Range range = new Range();
    private Window window = new Window();
    private Thresholds thresholds = new Thresholds();
    private Duration duration = new Duration();
    private Suppression suppression = new Suppression();

    public List<String> getSupportedProtocols() { return supportedProtocols; }
    public void setSupportedProtocols(List<String> supportedProtocols) { this.supportedProtocols = supportedProtocols; }
    public Range getRange() { return range; }
    public void setRange(Range range) { this.range = range; }
    public Window getWindow() { return window; }
    public void setWindow(Window window) { this.window = window; }
    public Thresholds getThresholds() { return thresholds; }
    public void setThresholds(Thresholds thresholds) { this.thresholds = thresholds; }
    public Duration getDuration() { return duration; }
    public void setDuration(Duration duration) { this.duration = duration; }
    public Suppression getSuppression() { return suppression; }
    public void setSuppression(Suppression suppression) { this.suppression = suppression; }

    public static class Range {
        private double minVoltage = 2.0, maxVoltage = 5.0;
        private double minCurrent = -500.0, maxCurrent = 500.0;
        private double minTemp = -40.0, maxTemp = 85.0;
        private double minInsulation = 0.0, maxInsulation = 100000.0;
        // getters/setters 占位，实现阶段填充
        public double getMinVoltage() { return minVoltage; }
        public void setMinVoltage(double v) { this.minVoltage = v; }
        public double getMaxVoltage() { return maxVoltage; }
        public void setMaxVoltage(double v) { this.maxVoltage = v; }
        public double getMinCurrent() { return minCurrent; }
        public void setMinCurrent(double v) { this.minCurrent = v; }
        public double getMaxCurrent() { return maxCurrent; }
        public void setMaxCurrent(double v) { this.maxCurrent = v; }
        public double getMinTemp() { return minTemp; }
        public void setMinTemp(double v) { this.minTemp = v; }
        public double getMaxTemp() { return maxTemp; }
        public void setMaxTemp(double v) { this.maxTemp = v; }
        public double getMinInsulation() { return minInsulation; }
        public void setMinInsulation(double v) { this.minInsulation = v; }
        public double getMaxInsulation() { return maxInsulation; }
        public void setMaxInsulation(double v) { this.maxInsulation = v; }
    }

    public static class Window {
        private long sizeMs = 60_000;
        private int minSamples = 3;
        public long getSizeMs() { return sizeMs; }
        public void setSizeMs(long v) { this.sizeMs = v; }
        public int getMinSamples() { return minSamples; }
        public void setMinSamples(int v) { this.minSamples = v; }
    }

    public static class Thresholds {
        private double tempWarnC = 45, tempCritC = 60;
        private double overVWarn = 4.20, overVCrit = 4.30;
        private double underVWarn = 3.20, underVCrit = 2.80;
        private double overAWarn = 200, overACrit = 300;
        private double insulationWarnK = 500, insulationCritK = 100;
        private double thermalRisePerSec = 1.0;
        private double thermalTempBaseC = 50.0;
        // getters/setters 占位
        public double getTempWarnC() { return tempWarnC; }
        public void setTempWarnC(double v) { this.tempWarnC = v; }
        public double getTempCritC() { return tempCritC; }
        public void setTempCritC(double v) { this.tempCritC = v; }
        public double getOverVWarn() { return overVWarn; }
        public void setOverVWarn(double v) { this.overVWarn = v; }
        public double getOverVCrit() { return overVCrit; }
        public void setOverVCrit(double v) { this.overVCrit = v; }
        public double getUnderVWarn() { return underVWarn; }
        public void setUnderVWarn(double v) { this.underVWarn = v; }
        public double getUnderVCrit() { return underVCrit; }
        public void setUnderVCrit(double v) { this.underVCrit = v; }
        public double getOverAWarn() { return overAWarn; }
        public void setOverAWarn(double v) { this.overAWarn = v; }
        public double getOverACrit() { return overACrit; }
        public void setOverACrit(double v) { this.overACrit = v; }
        public double getInsulationWarnK() { return insulationWarnK; }
        public void setInsulationWarnK(double v) { this.insulationWarnK = v; }
        public double getInsulationCritK() { return insulationCritK; }
        public void setInsulationCritK(double v) { this.insulationCritK = v; }
        public double getThermalRisePerSec() { return thermalRisePerSec; }
        public void setThermalRisePerSec(double v) { this.thermalRisePerSec = v; }
        public double getThermalTempBaseC() { return thermalTempBaseC; }
        public void setThermalTempBaseC(double v) { this.thermalTempBaseC = v; }
    }

    public static class Duration {
        private long warnHoldMs = 10_000;
        private long critHoldMs = 3_000;
        private long recoveryHoldMs = 15_000;
        public long getWarnHoldMs() { return warnHoldMs; }
        public void setWarnHoldMs(long v) { this.warnHoldMs = v; }
        public long getCritHoldMs() { return critHoldMs; }
        public void setCritHoldMs(long v) { this.critHoldMs = v; }
        public long getRecoveryHoldMs() { return recoveryHoldMs; }
        public void setRecoveryHoldMs(long v) { this.recoveryHoldMs = v; }
    }

    public static class Suppression {
        private long windowMs = 60_000;
        public long getWindowMs() { return windowMs; }
        public void setWindowMs(long v) { this.windowMs = v; }
    }
}
