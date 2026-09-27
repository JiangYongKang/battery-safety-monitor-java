package com.github.highcumontoa.batterysafetymonitorjava.tests.support;

import com.github.highcumontoa.batterysafetymonitorjava.web.dto.TelemetryRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用遥测请求构造器：时间戳走确定性模拟时钟，便于复现。
 */
public final class TelemetryFactory {

    public static final long T0 = 1_700_000_000_000L;

    private TelemetryFactory() {
    }

    public static TelemetryRequest batch(String batchId, String packId, String protocol,
                                         long sentAtMs, List<Sample> samples) {
        TelemetryRequest req = new TelemetryRequest();
        req.batchId = batchId;
        req.packId = packId;
        req.protocolVersion = protocol;
        req.sentAtMs = sentAtMs;
        req.samples = new ArrayList<>();
        for (Sample s : samples) {
            TelemetryRequest.SampleDto dto = new TelemetryRequest.SampleDto();
            dto.seq = s.seq;
            dto.timestampMs = s.ts;
            dto.voltageV = s.v;
            dto.currentA = s.i;
            dto.tempC = s.t;
            dto.insulationKOhm = s.r;
            req.samples.add(dto);
        }
        return req;
    }

    public static List<Sample> ramp(int startSeq, long startTs, long stepMs, int count,
                                    double v, double i, double firstTemp, double tempStep, Double insulation) {
        List<Sample> list = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            list.add(new Sample(startSeq + k, startTs + k * stepMs, v, i, firstTemp + k * tempStep, insulation));
        }
        return list;
    }

    public static List<Sample> constant(int startSeq, long startTs, long stepMs, int count,
                                        double v, double i, double t, Double r) {
        return ramp(startSeq, startTs, stepMs, count, v, i, t, 0.0, r);
    }

    public static Sample sample(int seq, long ts, Double v, Double i, Double t, Double r) {
        return new Sample(seq, ts, v, i, t, r);
    }

    public static final class Sample {
        public final int seq;
        public final long ts;
        public final Double v, i, t, r;

        public Sample(int seq, long ts, Double v, Double i, Double t, Double r) {
            this.seq = seq;
            this.ts = ts;
            this.v = v;
            this.i = i;
            this.t = t;
            this.r = r;
        }
    }
}
