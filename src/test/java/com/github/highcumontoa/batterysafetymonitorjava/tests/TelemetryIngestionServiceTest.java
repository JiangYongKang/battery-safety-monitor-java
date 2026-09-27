package com.github.highcumontoa.batterysafetymonitorjava.tests;

import com.github.highcumontoa.batterysafetymonitorjava.alert.AlertService;
import com.github.highcumontoa.batterysafetymonitorjava.alert.AuditLog;
import com.github.highcumontoa.batterysafetymonitorjava.assess.AssessmentService;
import com.github.highcumontoa.batterysafetymonitorjava.ingest.SampleValidator;
import com.github.highcumontoa.batterysafetymonitorjava.ingest.TelemetryIngestionService;
import com.github.highcumontoa.batterysafetymonitorjava.model.IngestResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.ProtocolVersion;
import com.github.highcumontoa.batterysafetymonitorjava.model.RejectionReason;
import com.github.highcumontoa.batterysafetymonitorjava.model.SampleBatch;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 接入链路：拒绝原因可区分、重复去重、乱序防护、并发一致。 */
class TelemetryIngestionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestionServiceTest.class);
    private static final String PACK = "PACK-INGEST";

    private TelemetryIngestionService ingestionService;
    private AssessmentService assessmentService;

    @BeforeEach
    void setUp() {
        AuditLog auditLog = new AuditLog();
        AlertService alertService = new AlertService(auditLog);
        assessmentService = new AssessmentService(alertService, auditLog);
        ingestionService = new TelemetryIngestionService(new SampleValidator(), assessmentService);
    }

    private TelemetrySample sample(long seq, long ts, double temp) {
        TelemetrySample s = new TelemetrySample();
        s.setPackId(PACK);
        s.setSequenceNumber(seq);
        s.setTimestampMillis(ts);
        s.setVoltage(400.0);
        s.setCurrent(50.0);
        s.setTemperature(temp);
        s.setInsulationOk(true);
        return s;
    }

    private SampleBatch batchOf(List<TelemetrySample> samples) {
        SampleBatch b = new SampleBatch();
        b.setBatchId("batch-" + System.nanoTime());
        b.setPackId(PACK);
        b.setProtocolVersion(ProtocolVersion.V2);
        b.setSamples(samples);
        return b;
    }

    @Test
    void invalidSamplesRejectedWithDistinguishableReasons() {
        TelemetrySample missingTemp = sample(1, 1_000_000L, 35.0);
        missingTemp.setTemperature(null);
        TelemetrySample outOfRange = sample(2, 1_001_000L, 35.0);
        outOfRange.setVoltage(9999.0);
        TelemetrySample missingInsulation = sample(3, 1_002_000L, 35.0);
        missingInsulation.setInsulationOk(null);
        TelemetrySample ok = sample(4, 1_003_000L, 35.0);

        IngestResult result = ingestionService.ingest(
                batchOf(List.of(missingTemp, outOfRange, missingInsulation, ok)));
        log.info("INPUT 4 samples (missing temp / voltage 9999V / V2 missing insulation / ok)");
        log.info("RESULT accepted={} rejected={} reasons={}", result.acceptedCount(), result.rejectedCount(),
                result.rejections().stream().map(r -> r.reason() + ":" + r.detail()).toList());

        assertEquals(1, result.acceptedCount());
        assertEquals(3, result.rejectedCount());
        List<RejectionReason> reasons = result.rejections().stream()
                .map(IngestResult.RejectedSample::reason).toList();
        assertTrue(reasons.contains(RejectionReason.MISSING_REQUIRED_FIELD));
        assertTrue(reasons.contains(RejectionReason.VALUE_OUT_OF_RANGE));
        assertTrue(reasons.contains(RejectionReason.PROTOCOL_REQUIRED_FIELD_MISSING));
    }

    @Test
    void duplicateSequenceRejectedAndNotCountedTwice() {
        SampleBatch batch = batchOf(List.of(sample(1, 1_000_000L, 35.0), sample(2, 1_001_000L, 36.0)));
        IngestResult first = ingestionService.ingest(batch);
        IngestResult second = ingestionService.ingest(batch); // 同批次重复上报
        log.info("INPUT same batch twice, seqs=[1,2]");
        log.info("RESULT first accepted={} second accepted={} second rejections={}",
                first.acceptedCount(), second.acceptedCount(),
                second.rejections().stream().map(r -> r.reason() + ":" + r.detail()).toList());

        assertEquals(2, first.acceptedCount());
        assertEquals(0, second.acceptedCount());
        assertEquals(2, second.rejectedCount());
        assertTrue(second.rejections().stream()
                .allMatch(r -> r.reason() == RejectionReason.DUPLICATE_SAMPLE));
    }

    @Test
    void outOfOrderAcceptedButDoesNotRegressState() {
        // 先推到 WARNING：6 条 60°C
        List<TelemetrySample> hot = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            hot.add(sample(i, 1_000_000L + i * 1000L, 60.0));
        }
        ingestionService.ingest(batchOf(hot));
        String stateAfterHot = assessmentService.currentState(PACK).name();
        log.info("INPUT 6x60C then out-of-order 25C with old timestamp");
        log.info("STATE after hot={}", stateAfterHot);
        assertEquals("WARNING", stateAfterHot);

        // 乱序：新序号但时间戳早于窗口最新 -> 受理但不入窗、不回退
        IngestResult stale = ingestionService.ingest(batchOf(List.of(sample(7, 1_000_500L, 25.0))));
        String stateAfterStale = assessmentService.currentState(PACK).name();
        log.info("RESULT stale accepted={} STATE after stale={}", stale.acceptedCount(), stateAfterStale);
        assertEquals(1, stale.acceptedCount());
        assertEquals("WARNING", stateAfterStale);
    }

    @Test
    void concurrentDuplicateBatchesAcceptedExactlyOnce() throws InterruptedException {
        int threads = 8;
        int samplesPerBatch = 10;
        SampleBatch shared = batchOf(new ArrayList<>());
        List<TelemetrySample> samples = new ArrayList<>();
        for (int i = 1; i <= samplesPerBatch; i++) {
            samples.add(sample(i, 1_000_000L + i * 1000L, 35.0));
        }
        shared.setSamples(samples);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger totalAccepted = new AtomicInteger();
        AtomicInteger totalDuplicates = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                IngestResult r = ingestionService.ingest(shared);
                totalAccepted.addAndGet(r.acceptedCount());
                totalDuplicates.addAndGet((int) r.rejections().stream()
                        .filter(x -> x.reason() == RejectionReason.DUPLICATE_SAMPLE).count());
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("INPUT {} threads x same batch of {} samples", threads, samplesPerBatch);
        log.info("RESULT totalAccepted={} totalDuplicates={}", totalAccepted.get(), totalDuplicates.get());
        assertEquals(samplesPerBatch, totalAccepted.get());
        assertEquals(samplesPerBatch * (threads - 1), totalDuplicates.get());
    }
}
