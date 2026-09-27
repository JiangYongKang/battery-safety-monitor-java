package com.github.highcumontoa.batterysafetymonitorjava.ingest;

import com.github.highcumontoa.batterysafetymonitorjava.model.ProtocolVersion;
import com.github.highcumontoa.batterysafetymonitorjava.model.RejectionReason;
import com.github.highcumontoa.batterysafetymonitorjava.model.SampleVerdict;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;
import org.springframework.stereotype.Component;

/**
 * 采样校验：通用必填项、协议必填项、物理量程。
 * 任一规则不满足即给出可区分的拒绝原因，不静默丢弃。
 */
@Component
public class SampleValidator {

    // 物理量程（包级）
    public static final double MIN_VOLTAGE_V = 0.0;
    public static final double MAX_VOLTAGE_V = 1000.0;
    public static final double MIN_CURRENT_A = -500.0;
    public static final double MAX_CURRENT_A = 500.0;
    public static final double MIN_TEMPERATURE_C = -40.0;
    public static final double MAX_TEMPERATURE_C = 125.0;

    public SampleVerdict validate(TelemetrySample sample, ProtocolVersion version) {
        if (sample == null) {
            return SampleVerdict.rejected(RejectionReason.MISSING_REQUIRED_FIELD, "sample is null");
        }
        // ---- 通用必填项 ----
        if (isBlank(sample.getPackId())) {
            return SampleVerdict.rejected(RejectionReason.MISSING_REQUIRED_FIELD, "packId is missing");
        }
        if (sample.getSequenceNumber() == null) {
            return SampleVerdict.rejected(RejectionReason.MISSING_REQUIRED_FIELD, "sequenceNumber is missing");
        }
        if (sample.getSequenceNumber() < 0) {
            return SampleVerdict.rejected(RejectionReason.VALUE_OUT_OF_RANGE,
                    "sequenceNumber must be >= 0, got " + sample.getSequenceNumber());
        }
        if (sample.getTimestampMillis() == null) {
            return SampleVerdict.rejected(RejectionReason.MISSING_REQUIRED_FIELD, "timestampMillis is missing");
        }
        if (sample.getTimestampMillis() <= 0) {
            return SampleVerdict.rejected(RejectionReason.VALUE_OUT_OF_RANGE,
                    "timestampMillis must be > 0, got " + sample.getTimestampMillis());
        }
        if (sample.getVoltage() == null) {
            return SampleVerdict.rejected(RejectionReason.MISSING_REQUIRED_FIELD, "voltage is missing");
        }
        if (sample.getCurrent() == null) {
            return SampleVerdict.rejected(RejectionReason.MISSING_REQUIRED_FIELD, "current is missing");
        }
        if (sample.getTemperature() == null) {
            return SampleVerdict.rejected(RejectionReason.MISSING_REQUIRED_FIELD, "temperature is missing");
        }

        // ---- 协议必填项 ----
        if (version == ProtocolVersion.V2 && sample.getInsulationOk() == null) {
            return SampleVerdict.rejected(RejectionReason.PROTOCOL_REQUIRED_FIELD_MISSING,
                    "protocol V2 requires insulationOk");
        }

        // ---- 量程 ----
        if (!inRange(sample.getVoltage(), MIN_VOLTAGE_V, MAX_VOLTAGE_V)) {
            return SampleVerdict.rejected(RejectionReason.VALUE_OUT_OF_RANGE,
                    "voltage " + sample.getVoltage() + "V out of range [" + MIN_VOLTAGE_V + "," + MAX_VOLTAGE_V + "]");
        }
        if (!inRange(sample.getCurrent(), MIN_CURRENT_A, MAX_CURRENT_A)) {
            return SampleVerdict.rejected(RejectionReason.VALUE_OUT_OF_RANGE,
                    "current " + sample.getCurrent() + "A out of range [" + MIN_CURRENT_A + "," + MAX_CURRENT_A + "]");
        }
        if (!inRange(sample.getTemperature(), MIN_TEMPERATURE_C, MAX_TEMPERATURE_C)) {
            return SampleVerdict.rejected(RejectionReason.VALUE_OUT_OF_RANGE,
                    "temperature " + sample.getTemperature() + "C out of range [" + MIN_TEMPERATURE_C + "," + MAX_TEMPERATURE_C + "]");
        }
        return SampleVerdict.ok();
    }

    private static boolean inRange(Double value, double min, double max) {
        return value != null && !value.isNaN() && !value.isInfinite() && value >= min && value <= max;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
