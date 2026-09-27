package com.github.highcumontoa.batterysafetymonitorjava.simulator;

import com.github.highcumontoa.batterysafetymonitorjava.config.SimulatorProperties;
import com.github.highcumontoa.batterysafetymonitorjava.service.IngestService;
import com.github.highcumontoa.batterysafetymonitorjava.web.dto.TelemetryRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地模拟遥测数据源（无真实设备/外部服务）。
 *
 * 演示剧本（确定性，无随机）：
 * - pack-PACK-A（协议 v2，含绝缘）：温度持续爬升，先后触发温度预警 → 严重过温 → 热失控；
 * - pack-PACK-B（协议 v1，无绝缘）：始终正常。
 * 时间戳使用模拟时钟（从固定起点按步长推进），保证结论可复现。
 */
@Component
public class SimulatorRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);
    private static final long SIM_EPOCH = 1_700_000_000_000L;

    private final SimulatorProperties props;
    private final IngestService ingestService;
    private final AtomicLong simClock = new AtomicLong(SIM_EPOCH);
    private final AtomicInteger batchCounter = new AtomicInteger();
    private ScheduledExecutorService executor;

    public SimulatorRunner(SimulatorProperties props, IngestService ingestService) {
        this.props = props;
        this.ingestService = ingestService;
    }

    @PostConstruct
    public void start() {
        if (!props.isEnabled()) {
            log.info("[模拟器] 未启用（本地演示可用 --battery.simulator.enabled=true 开启）");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telemetry-simulator");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tickSafely, 500, props.getIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("[模拟器] 已启动，每 {}ms 上报一批，每批 {} 条", props.getIntervalMs(), props.getSamplesPerBatch());
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void tickSafely() {
        try {
            tick("PACK-A", "v2", true);
            tick("PACK-B", "v1", false);
        } catch (Exception e) {
            log.warn("[模拟器] tick 异常: {}", e.toString());
        }
    }

    private void tick(String packId, String protocol, boolean heating) {
        int batchNo = batchCounter.incrementAndGet();
        TelemetryRequest req = new TelemetryRequest();
        req.batchId = "BATCH-" + packId + "-" + batchNo;
        req.packId = packId;
        req.protocolVersion = protocol;
        List<TelemetryRequest.SampleDto> samples = new ArrayList<>();
        long latestTs = simClock.addAndGet(props.getSampleStepMs());
        req.sentAtMs = latestTs;
        for (int i = 0; i < props.getSamplesPerBatch(); i++) {
            latestTs = simClock.addAndGet(props.getSampleStepMs());
            TelemetryRequest.SampleDto s = new TelemetryRequest.SampleDto();
            s.seq = batchNo * 100 + i;
            s.timestampMs = latestTs;
            if (heating) {
                // 每采样步长升温约 2.6℃：温升速率 ~1.3℃/s，超过热失控阈值
                s.tempC = 25.0 + (batchNo * props.getSamplesPerBatch() + i) * 2.6;
                s.voltageV = 4.0;
                s.currentA = 120.0;
                s.insulationKOhm = 800.0;
            } else {
                s.tempC = 30.0;
                s.voltageV = 3.7;
                s.currentA = 50.0;
            }
            samples.add(s);
        }
        req.samples = samples;
        log.info("[模拟器] 上报 packId={} batch={} 样本时间范围首={}",
                packId, req.batchId, samples.get(0).timestampMs);
        ingestService.ingest(req);
    }
}
