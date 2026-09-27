package com.github.highcumontoa.batterysafetymonitorjava.tests;

import com.github.highcumontoa.batterysafetymonitorjava.alert.AlertService;
import com.github.highcumontoa.batterysafetymonitorjava.alert.AuditLog;
import com.github.highcumontoa.batterysafetymonitorjava.model.AssessmentResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.DisposalAction;
import com.github.highcumontoa.batterysafetymonitorjava.model.HealthState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 告警与处置：持续时间触发、抑制窗口、恢复后重触发、旧结论防护、并发不重复。 */
class AlertServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AlertServiceTest.class);
    private static final String PACK = "PACK-ALERT";
    private static final long BASE_TS = 2_000_000L;

    private AlertService alertService;
    private AuditLog auditLog;

    @BeforeEach
    void setUp() {
        auditLog = new AuditLog();
        alertService = new AlertService(auditLog);
    }

    private AssessmentResult result(HealthState state, long atMillis) {
        return new AssessmentResult(PACK, state, 50.0, 0.6, 10,
                "test basis state=" + state, atMillis);
    }

    @Test
    void warningRequiresSustainedDuration() {
        // WARNING 首次出现：持续 0ms < 2000ms，不触发
        alertService.onAssessment(result(HealthState.WARNING, BASE_TS));
        log.info("INPUT WARNING at t=0 (duration 0ms < {}ms)", AlertService.WARNING_MIN_DURATION_MILLIS);
        log.info("JUDGE alerts={} expect 0", alertService.alertsFor(PACK).size());
        assertEquals(0, alertService.alertsFor(PACK).size());

        // 持续 1s：仍不足
        alertService.onAssessment(result(HealthState.WARNING, BASE_TS + 1000));
        assertEquals(0, alertService.alertsFor(PACK).size());

        // 持续 2.5s：触发
        alertService.onAssessment(result(HealthState.WARNING, BASE_TS + 2500));
        log.info("INPUT WARNING sustained 2500ms -> alerts={} action={}",
                alertService.alertsFor(PACK).size(),
                alertService.alertsFor(PACK).isEmpty() ? "-" : alertService.alertsFor(PACK).get(0).action());
        assertEquals(1, alertService.alertsFor(PACK).size());
        assertEquals(DisposalAction.LIMIT_POWER, alertService.alertsFor(PACK).get(0).action());
    }

    @Test
    void criticalTriggersImmediately() {
        alertService.onAssessment(result(HealthState.CRITICAL, BASE_TS));
        log.info("INPUT CRITICAL at t=0 -> alerts={} action={}",
                alertService.alertsFor(PACK).size(), alertService.alertsFor(PACK).get(0).action());
        assertEquals(1, alertService.alertsFor(PACK).size());
        assertEquals(DisposalAction.EMERGENCY_STOP, alertService.alertsFor(PACK).get(0).action());
    }

    @Test
    void sameFaultSuppressedWithinEpisode() {
        alertService.onAssessment(result(HealthState.CRITICAL, BASE_TS));
        // 抑制窗口内同一故障反复触发
        for (int i = 1; i <= 5; i++) {
            alertService.onAssessment(result(HealthState.CRITICAL, BASE_TS + i * 1000));
        }
        long suppressed = auditLog.recordsFor(PACK).stream()
                .filter(r -> r.eventType().equals("ALERT_SUPPRESSED")).count();
        log.info("INPUT CRITICAL x6 within suppression window {}ms", AlertService.SUPPRESSION_WINDOW_MILLIS);
        log.info("JUDGE alerts={} suppressed={} expect alerts=1", alertService.alertsFor(PACK).size(), suppressed);
        assertEquals(1, alertService.alertsFor(PACK).size());
        assertEquals(5, suppressed);
    }

    @Test
    void retriggerAllowedOnlyAfterRecovery() {
        // 第一次故障期
        alertService.onAssessment(result(HealthState.CRITICAL, BASE_TS));
        // 未恢复，即使超过抑制窗口也不重复告警（同一故障期）
        alertService.onAssessment(result(HealthState.CRITICAL,
                BASE_TS + AlertService.SUPPRESSION_WINDOW_MILLIS + 10_000));
        log.info("JUDGE still in episode beyond suppression window -> alerts={} expect 1",
                alertService.alertsFor(PACK).size());
        assertEquals(1, alertService.alertsFor(PACK).size());

        // 恢复到 NORMAL：故障期关闭
        alertService.onAssessment(result(HealthState.NORMAL,
                BASE_TS + AlertService.SUPPRESSION_WINDOW_MILLIS + 20_000));
        // 再次越限：允许重新触发
        alertService.onAssessment(result(HealthState.CRITICAL,
                BASE_TS + AlertService.SUPPRESSION_WINDOW_MILLIS + 30_000));
        log.info("JUDGE after RECOVERY re-trigger -> alerts={} expect 2", alertService.alertsFor(PACK).size());
        assertEquals(2, alertService.alertsFor(PACK).size());
        assertTrue(auditLog.recordsFor(PACK).stream().anyMatch(r -> r.eventType().equals("RECOVERY")));
    }

    @Test
    void staleAssessmentIgnored() {
        alertService.onAssessment(result(HealthState.CRITICAL, BASE_TS + 10_000));
        // 后到的旧结论（时间戳更早）不得覆盖、不得重复触发
        alertService.onAssessment(result(HealthState.NORMAL, BASE_TS + 5_000));
        alertService.onAssessment(result(HealthState.CRITICAL, BASE_TS + 8_000));
        log.info("INPUT stale NORMAL(t+5s) & stale CRITICAL(t+8s) after CRITICAL(t+10s)");
        log.info("JUDGE alerts={} expect 1, no RECOVERY event", alertService.alertsFor(PACK).size());
        assertEquals(1, alertService.alertsFor(PACK).size());
        assertTrue(auditLog.recordsFor(PACK).stream().noneMatch(r -> r.eventType().equals("RECOVERY")));
    }

    @Test
    void concurrentAssessmentsProduceSingleAlert() throws InterruptedException {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<AssessmentResult> results = IntStream.range(0, threads)
                .mapToObj(i -> result(HealthState.CRITICAL, BASE_TS + i))
                .toList();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                alertService.onAssessment(results.get(idx));
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("INPUT {} concurrent CRITICAL assessments", threads);
        log.info("JUDGE alerts={} disposals={} expect exactly 1 each",
                alertService.alertsFor(PACK).size(),
                auditLog.recordsFor(PACK).stream().filter(r -> r.eventType().equals("DISPOSAL_EXECUTED")).count());
        assertEquals(1, alertService.alertsFor(PACK).size());
        assertEquals(1, auditLog.recordsFor(PACK).stream()
                .filter(r -> r.eventType().equals("DISPOSAL_EXECUTED")).count());
    }

    @Test
    void disposalIsAuditedWithTriggerBasis() {
        alertService.onAssessment(result(HealthState.CRITICAL, BASE_TS));
        var disposals = auditLog.recordsFor(PACK).stream()
                .filter(r -> r.eventType().equals("DISPOSAL_EXECUTED")).toList();
        log.info("JUDGE disposal audit={}", disposals);
        assertEquals(1, disposals.size());
        assertTrue(disposals.get(0).detail().contains("EMERGENCY_STOP"));
        assertTrue(disposals.get(0).detail().contains("test basis"));
    }
}
