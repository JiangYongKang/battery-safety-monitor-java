package com.github.highcumontoa.batterysafetymonitorjava.tests;

import com.github.highcumontoa.batterysafetymonitorjava.ingest.SampleValidator;
import com.github.highcumontoa.batterysafetymonitorjava.model.ProtocolVersion;
import com.github.highcumontoa.batterysafetymonitorjava.model.RejectionReason;
import com.github.highcumontoa.batterysafetymonitorjava.model.SampleVerdict;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 采样校验：必填项、协议必填项、量程边界。 */
class SampleValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(SampleValidatorTest.class);

    private final SampleValidator validator = new SampleValidator();

    private TelemetrySample validSample() {
        TelemetrySample s = new TelemetrySample();
        s.setPackId("PACK-T");
        s.setSequenceNumber(1L);
        s.setTimestampMillis(1_000_000L);
        s.setVoltage(400.0);
        s.setCurrent(50.0);
        s.setTemperature(35.0);
        s.setInsulationOk(true);
        return s;
    }

    private void printVerdict(String caseName, SampleVerdict v) {
        log.info("CASE={} accepted={} reason={} detail={}", caseName, v.accepted(), v.reason(), v.detail());
    }

    @Test
    void validSampleAccepted() {
        SampleVerdict v = validator.validate(validSample(), ProtocolVersion.V2);
        printVerdict("validSampleAccepted", v);
        assertTrue(v.accepted());
    }

    @Test
    void missingFieldsRejectedWithDistinguishableReasons() {
        TelemetrySample noPack = validSample();
        noPack.setPackId(null);
        SampleVerdict v1 = validator.validate(noPack, ProtocolVersion.V1);
        printVerdict("missing packId", v1);
        assertFalse(v1.accepted());
        assertEquals(RejectionReason.MISSING_REQUIRED_FIELD, v1.reason());

        TelemetrySample noVoltage = validSample();
        noVoltage.setVoltage(null);
        SampleVerdict v2 = validator.validate(noVoltage, ProtocolVersion.V1);
        printVerdict("missing voltage", v2);
        assertEquals(RejectionReason.MISSING_REQUIRED_FIELD, v2.reason());

        TelemetrySample noSeq = validSample();
        noSeq.setSequenceNumber(null);
        SampleVerdict v3 = validator.validate(noSeq, ProtocolVersion.V1);
        printVerdict("missing sequenceNumber", v3);
        assertEquals(RejectionReason.MISSING_REQUIRED_FIELD, v3.reason());
    }

    @Test
    void protocolV2RequiresInsulation() {
        TelemetrySample s = validSample();
        s.setInsulationOk(null);
        SampleVerdict v2 = validator.validate(s, ProtocolVersion.V2);
        printVerdict("V2 missing insulationOk", v2);
        assertEquals(RejectionReason.PROTOCOL_REQUIRED_FIELD_MISSING, v2.reason());

        // V1 不要求绝缘字段
        SampleVerdict v1 = validator.validate(s, ProtocolVersion.V1);
        printVerdict("V1 missing insulationOk", v1);
        assertTrue(v1.accepted());
    }

    @Test
    void outOfRangeRejected() {
        TelemetrySample s = validSample();
        s.setVoltage(1200.0);
        SampleVerdict v = validator.validate(s, ProtocolVersion.V1);
        printVerdict("voltage 1200V", v);
        assertEquals(RejectionReason.VALUE_OUT_OF_RANGE, v.reason());

        TelemetrySample s2 = validSample();
        s2.setTemperature(200.0);
        SampleVerdict v2result = validator.validate(s2, ProtocolVersion.V1);
        printVerdict("temperature 200C", v2result);
        assertEquals(RejectionReason.VALUE_OUT_OF_RANGE, v2result.reason());

        TelemetrySample s3 = validSample();
        s3.setCurrent(Double.NaN);
        SampleVerdict v3 = validator.validate(s3, ProtocolVersion.V1);
        printVerdict("current NaN", v3);
        assertEquals(RejectionReason.VALUE_OUT_OF_RANGE, v3.reason());
    }

    @Test
    void boundaryValuesAccepted() {
        TelemetrySample s = validSample();
        s.setVoltage(SampleValidator.MIN_VOLTAGE_V);
        s.setCurrent(SampleValidator.MAX_CURRENT_A);
        s.setTemperature(SampleValidator.MAX_TEMPERATURE_C);
        SampleVerdict v = validator.validate(s, ProtocolVersion.V1);
        printVerdict("boundary min/max", v);
        assertTrue(v.accepted());

        TelemetrySample s2 = validSample();
        s2.setVoltage(SampleValidator.MAX_VOLTAGE_V);
        s2.setCurrent(SampleValidator.MIN_CURRENT_A);
        s2.setTemperature(SampleValidator.MIN_TEMPERATURE_C);
        SampleVerdict v2 = validator.validate(s2, ProtocolVersion.V1);
        printVerdict("boundary max/min", v2);
        assertTrue(v2.accepted());
    }
}
