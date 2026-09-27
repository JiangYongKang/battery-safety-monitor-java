package com.github.highcumontoa.batterysafetymonitorjava.tests;

import com.github.highcumontoa.batterysafetymonitorjava.model.IngestResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.PackSnapshot;
import com.github.highcumontoa.batterysafetymonitorjava.model.RejectReason;
import com.github.highcumontoa.batterysafetymonitorjava.service.AlarmService;
import com.github.highcumontoa.batterysafetymonitorjava.service.AssessmentService;
import com.github.highcumontoa.batterysafetymonitorjava.service.IngestService;
import com.github.highcumontoa.batterysafetymonitorjava.tests.support.TelemetryFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.highcumontoa.batterysafetymonitorjava.tests.support.TelemetryFactory.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发一致性：同一批次并发上报仅一次生效；不同批次并发上报不串包、不丢累计；
 * 故障成立时并发评估/重复触发不产生重复外部告警。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConcurrencyConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyConsistencyTest.class);

    @Autowired
    private IngestService ingestService;
    @Autowired
    private AssessmentService assessmentService;
    @Autowired
    private AlarmService alarmService;

    @Test
    void sameBatchReportedConcurrentlyIsAcceptedExactlyOnce() throws Exception {
        var req = TelemetryFactory.batch("CB-1", "P-CB", "v2", T0 + 12_000,
                TelemetryFactory.constant(0, T0, 3_000, 5, 3.7, 10.0, 30.0, 800.0));

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<IngestResult>> futures = new ArrayList<>();
        for (int k = 0; k < threads; k++) {
            futures.add(pool.submit(() -> ingestService.ingest(req)));
        }
        int accepted = 0;
        int duplicated = 0;
        for (Future<IngestResult> f : futures) {
            IngestResult r = f.get(10, TimeUnit.SECONDS);
            if (r.accepted()) {
                accepted++;
            } else if (r.batchRejectReason() == RejectReason.DUPLICATE_BATCH) {
                duplicated++;
            }
        }
        pool.shutdown();
        log.info("[并发用例] 同一批次{}线程并发上报 -> 生效={} 重复拒绝={} (期望 1/{})", threads, accepted, duplicated, threads - 1);
        assertEquals(1, accepted);
        assertEquals(threads - 1, duplicated);

        // 正常数据：无活动故障，状态保持 NORMAL，未因并发重复评估产生异常结论
        PackSnapshot snap = assessmentService.snapshot("P-CB");
        log.info("[并发用例] 幂等生效后状态 -> state={} 活动故障={}", snap.state(), countFaults(snap));
        assertEquals(0, countFaults(snap));
    }

    @Test
    void concurrentBatchesHaveDeterministicOutcomesAndNoStateRollback() throws Exception {
        int batches = 12;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<IngestResult>> futures = new ArrayList<>();
        for (int b = 0; b < batches; b++) {
            int idx = b;
            long sentAt = T0 + 10_000 + idx * 10_000L;
            var samples = TelemetryFactory.constant(idx * 10, T0 + idx * 10_000L, 2_000, 3,
                    3.7, 10.0, 30.0, 800.0);
            var req = TelemetryFactory.batch("CBX-" + idx, "P-CBX", "v2", sentAt, samples);
            futures.add(pool.submit(() -> ingestService.ingest(req)));
        }
        int accepted = 0;
        int stale = 0;
        for (Future<IngestResult> f : futures) {
            IngestResult r = f.get(10, TimeUnit.SECONDS);
            // 每个并发批次都有确定结论：生效或 STALE_BATCH，绝不出现静默丢失/重复累计
            assertTrue(r.accepted() || r.batchRejectReason() == RejectReason.STALE_BATCH,
                    "非确定结果: " + r);
            if (r.accepted()) {
                accepted++;
            } else {
                stale++;
            }
        }
        pool.shutdown();
        log.info("[并发用例] 12个不同sentAt批次乱序并发 -> 生效={} 乱序拒绝={} 总数=12，结论确定无静默丢失",
                accepted, stale);
        assertEquals(batches, accepted + stale);
        assertTrue(accepted >= 1);

        // 最终游标必须是最大 sentAt（旧批次即使先被判 stale 也不会回退游标）
        PackSnapshot snap = assessmentService.snapshot("P-CBX");
        assertEquals(T0 + batches * 10_000L, snap.lastBatchSentAtMs());

        // 同批次再次重放 -> DUPLICATE_BATCH（若此前已生效），旧批次 -> STALE_BATCH，均可区分
        IngestResult replayOld = ingestService.ingest(
                TelemetryFactory.batch("CBX-REPLAY", "P-CBX", "v2", T0 + 5_000,
                        TelemetryFactory.constant(900, T0 + 200_000, 2_000, 2, 3.7, 10.0, 30.0, 800.0)));
        log.info("[并发用例] 旧批次重放 -> accepted={} reason={}", replayOld.accepted(), replayOld.batchRejectReason());
        assertEquals(RejectReason.STALE_BATCH, replayOld.batchRejectReason());
    }

    @Test
    void concurrentEvaluationProducesSingleAlarmAndSingleCutoff() throws Exception {
        // 先灌入足以触发严重过温的采样（但先不评估——接入时已联动评估，故改为直接灌正常数据后并发 evaluateAll 不会新增故障；
        // 这里改为并发提交多个"各自导致故障"的相同批次，验证只有一次外部告警）
        var req = TelemetryFactory.batch("CCA-1", "P-CCA", "v2", T0 + 4_000,
                TelemetryFactory.constant(0, T0, 2_000, 3, 3.7, 50.0, 62.0, 800.0));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<IngestResult>> futures = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            futures.add(pool.submit(() -> ingestService.ingest(req)));
        }
        int accepted = 0;
        for (Future<IngestResult> f : futures) {
            if (f.get(10, TimeUnit.SECONDS).accepted()) {
                accepted++;
            }
        }
        // 故障持续期间并发重复评估
        List<Callable<Void>> evals = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            evals.add(() -> {
                assessmentService.evaluateAll();
                return null;
            });
        }
        pool.invokeAll(evals).forEach(f -> {
            try {
                f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        pool.shutdown();

        long cutoffs = alarmService.actions("P-CCA").stream()
                .filter(a -> a.action().name().equals("RELAY_CUTOFF")).count();
        long alarms = alarmService.alarms("P-CCA").size();
        log.info("[并发用例] 并发触发严重故障 -> 生效批次={} 外部告警={} RELAY_CUTOFF动作={} (期望1/1/1)",
                accepted, alarms, cutoffs);
        assertEquals(1, accepted);
        assertEquals(1, alarms);
        assertEquals(1, cutoffs);
    }

    private long countFaults(PackSnapshot snap) {
        return snap.faults().stream().filter(f -> f.active()).count();
    }
}
