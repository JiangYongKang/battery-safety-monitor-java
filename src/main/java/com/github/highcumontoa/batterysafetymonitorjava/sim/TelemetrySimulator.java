package com.github.highcumontoa.batterysafetymonitorjava.sim;

import com.github.highcumontoa.batterysafetymonitorjava.ingest.TelemetryIngestionService;
import com.github.highcumontoa.batterysafetymonitorjava.model.ProtocolVersion;
import com.github.highcumontoa.batterysafetymonitorjava.model.SampleBatch;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地模拟遥测源：不依赖真实设备，周期性为多个电池包生成并上报模拟采样。
 * <ul>
 *   <li>PACK-ALPHA（V2 协议）：正常工况，温度小幅波动；</li>
 *   <li>PACK-BRAVO（V1 协议）：缓慢升温，最终越过告警阈值，演示升级链路；</li>
 *   <li>PACK-CHARLIE（V2 协议）：运行一段时间后出现绝缘失效，演示 CRITICAL 处置。</li>
 * </ul>
 * 通过 simulator.enabled=true 开启。
 */
@Component
@ConditionalOnProperty(name = "simulator.enabled", havingValue = "true")
public class TelemetrySimulator {

    private static final Logger log = LoggerFactory.getLogger(TelemetrySimulator.class);

    private final TelemetryIngestionService ingestionService;
    private final AtomicLong tick = new AtomicLong();
    private final AtomicLong seqAlpha = new AtomicLong();
    private final AtomicLong seqBravo = new AtomicLong();
    private final AtomicLong seqCharlie = new AtomicLong();

    public TelemetrySimulator(TelemetryIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Scheduled(fixedDelayString = "${simulator.interval-ms:1000}", initialDelayString = "${simulator.initial-delay-ms:1000}")
    public void tick() {
        long t = tick.incrementAndGet();
        long now = System.currentTimeMillis();

        // PACK-ALPHA：正常工况（V2，含绝缘字段）
        ingest("PACK-ALPHA", ProtocolVersion.V2, seqAlpha, now,
                398 + jitter(4), 30 + jitter(10), 32 + jitter(2), true);

        // PACK-BRAVO：缓慢升温（V1，无绝缘字段），约 40 tick 后越过 55°C
        double bravoTemp = 35 + t * 0.6 + jitter(1);
        ingest("PACK-BRAVO", ProtocolVersion.V1, seqBravo, now,
                380 + jitter(3), 45 + jitter(8), bravoTemp, null);

        // PACK-CHARLIE：第 25 tick 起绝缘失效（V2）
        boolean insulationOk = t < 25;
        ingest("PACK-CHARLIE", ProtocolVersion.V2, seqCharlie, now,
                405 + jitter(3), 25 + jitter(5), 36 + jitter(2), insulationOk);

        if (t % 10 == 0) {
            log.info("SIMULATOR tick={} reported 3 packs", t);
        }
    }

    private void ingest(String packId, ProtocolVersion version, AtomicLong seq, long now,
                        double voltage, double current, double temperature, Boolean insulationOk) {
        TelemetrySample sample = new TelemetrySample();
        sample.setPackId(packId);
        sample.setSequenceNumber(seq.incrementAndGet());
        sample.setTimestampMillis(now);
        sample.setVoltage(round2(voltage));
        sample.setCurrent(round2(current));
        sample.setTemperature(round2(temperature));
        sample.setInsulationOk(insulationOk);

        SampleBatch batch = new SampleBatch();
        batch.setBatchId(UUID.randomUUID().toString());
        batch.setPackId(packId);
        batch.setProtocolVersion(version);
        batch.setSamples(List.of(sample));
        ingestionService.ingest(batch);
    }

    private static double jitter(double amplitude) {
        return ThreadLocalRandom.current().nextDouble(-amplitude, amplitude);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
