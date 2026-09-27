package com.github.highcumontoa.batterysafetymonitorjava.tests;

import com.github.highcumontoa.batterysafetymonitorjava.model.AssessmentResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.FaultKey;
import com.github.highcumontoa.batterysafetymonitorjava.model.HealthState;
import com.github.highcumontoa.batterysafetymonitorjava.model.PackSnapshot;
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

import static com.github.highcumontoa.batterysafetymonitorjava.tests.support.TelemetryFactory.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估与状态机：窗口不足/短暂波动不误报、阈值临界精确、持续时间满足才升级、
 * 恢复保持后才降级、热失控按温升速率判定。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AssessmentStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(AssessmentStateMachineTest.class);

    @Autowired
    private IngestService ingestService;
    @Autowired
    private AssessmentService assessmentService;

    private void send(String pack, String batch, long sentAt, List<TelemetryFactory.Sample> samples) {
        ingestService.ingest(TelemetryFactory.batch(batch, pack, "v2", sentAt, samples));
    }

    @Test
    void normalDataKeepsNormalState() {
        var samples = TelemetryFactory.constant(0, T0, 5_000, 5, 3.7, 50.0, 30.0, 800.0);
        send("P-N", "B-N-1", T0 + 20_000, samples);
        PackSnapshot snap = assessmentService.snapshot("P-N");
        log.info("[用例] 正常数据 -> state={} score={} 活动故障={}", snap.state(), snap.healthScore(), snap.faults().stream().filter(f -> f.active()).count());
        assertEquals(HealthState.NORMAL, snap.state());
        assertEquals(100, snap.healthScore());
        assertTrue(snap.timeline().isEmpty());
    }

    @Test
    void briefFluctuationAndInsufficientWindowDoNotTrigger() {
        List<TelemetryFactory.Sample> samples = new ArrayList<>();
        // 仅 2 条高温：低于 minSamples=3，且持续不足 warnHold=10s
        samples.add(TelemetryFactory.sample(0, T0, 3.7, 50.0, 50.0, 800.0));
        samples.add(TelemetryFactory.sample(1, T0 + 2_000, 3.7, 50.0, 50.0, 800.0));
        send("P-F", "B-F-1", T0 + 2_000, samples);
        PackSnapshot snap = assessmentService.snapshot("P-F");
        log.info("[用例] 短暂高温(2条,2s) -> state={} 期望NORMAL(窗口不足)", snap.state());
        assertEquals(HealthState.NORMAL, snap.state());

        // 再来 3 条但时间跨度仍不足 10s -> 仍不触发
        var more = TelemetryFactory.constant(2, T0 + 4_000, 2_000, 3, 3.7, 50.0, 50.0, 800.0);
        send("P-F", "B-F-2", T0 + 8_000, more);
        snap = assessmentService.snapshot("P-F");
        log.info("[用例] 持续高温但跨度6s<10s -> state={} 期望NORMAL(持续时间不足)", snap.state());
        assertEquals(HealthState.NORMAL, snap.state());
    }

    @Test
    void thresholdBoundaryIsInclusiveAndHoldTriggersWarn() {
        // 精确等于阈值 45.0℃（>= 判定），5 条 ×3s = 12s ≥ warnHold 10s，应触发 TEMP_WARN
        var samples = TelemetryFactory.constant(0, T0, 3_000, 5, 3.7, 50.0, 45.0, 800.0);
        send("P-B", "B-B-1", T0 + 12_000, samples);
        PackSnapshot snap = assessmentService.snapshot("P-B");
        log.info("[用例] 温度精确45.0℃持续12s -> state={} score={} 活动故障={}",
                snap.state(), snap.healthScore(), activeKeys(snap));
        assertEquals(HealthState.WARNING, snap.state());
        assertTrue(activeKeys(snap).contains(FaultKey.TEMP_WARN.name()));
        assertFalse(activeKeys(snap).contains(FaultKey.TEMP_CRIT.name()));
        assertEquals(92, snap.healthScore());

        // 低 0.1℃ 则不触发：新包做边界对照
        var below = TelemetryFactory.constant(0, T0, 3_000, 5, 3.7, 50.0, 44.9, 800.0);
        send("P-B2", "B-B2-1", T0 + 12_000, below);
        PackSnapshot snap2 = assessmentService.snapshot("P-B2");
        log.info("[用例] 温度44.9℃<阈值 -> state={} 期望NORMAL", snap2.state());
        assertEquals(HealthState.NORMAL, snap2.state());
    }

    @Test
    void stateUpgradesToCriticalAndThermalRunaway() {
        // 温度从 55℃ 快速爬升，步长 2s、每步 +3℃ => 1.5℃/s，超过热失控阈值 1.0℃/s
        // 同时跨过 crit 60℃；critHold=3s，3 条 2s 间隔即满足
        var samples = TelemetryFactory.ramp(0, T0, 2_000, 8, 4.0, 120.0, 55.0, 3.0, 800.0);
        send("P-C", "B-C-1", T0 + 14_000, samples);
        PackSnapshot snap = assessmentService.snapshot("P-C");
        log.info("[用例] 快速升温序列 -> state={} score={} 活动故障={} 时间线={}",
                snap.state(), snap.healthScore(), activeKeys(snap), snap.timeline());
        assertEquals(HealthState.THERMAL_RUNAWAY, snap.state());
        assertTrue(activeKeys(snap).contains(FaultKey.THERMAL_RUNAWAY.name()));
        // 状态时间线必须单调：NORMAL -> WARNING -> CRITICAL -> THERMAL_RUNAWAY（允许跳级但不允许回退）
        for (int k = 1; k < snap.timeline().size(); k++) {
            assertTrue(snap.timeline().get(k).to().level() >= snap.timeline().get(k - 1).to().level(),
                    "状态迁移必须单调向上: " + snap.timeline());
        }
    }

    @Test
    void recoveryRequiresSustainedNormalBeforeDowngrade() {
        var hot = TelemetryFactory.constant(0, T0, 3_000, 5, 3.7, 50.0, 50.0, 800.0);
        send("P-R", "B-R-1", T0 + 12_000, hot);
        assertEquals(HealthState.WARNING, assessmentService.snapshot("P-R").state());

        // 恢复 10s（< recoveryHold 15s）：不得降级（防抖动）
        var recoverShort = TelemetryFactory.constant(5, T0 + 15_000, 3_000, 4, 3.7, 50.0, 30.0, 800.0);
        send("P-R", "B-R-2", T0 + 24_000, recoverShort);
        AssessmentResult mid = assessmentService.evaluateAll().stream()
                .filter(r -> r.packId().equals("P-R")).findFirst().orElseThrow();
        log.info("[用例] 恢复10s<15s -> state={} 期望保持WARNING", mid.state());
        assertEquals(HealthState.WARNING, mid.state());

        // 滑动窗口 60s：必须让全部违例样本滑出窗口，且无违例再保持 recoveryHold=15s 才降级
        // 正常样本从 +15s 持续到 +81s（23 条 ×3s），违例样本(最晚+12s)在 latest=72s 时滑出，
        // 之后再保持 15s 无违例 -> latest=87s 时降级 NORMAL
        var recoverLong = TelemetryFactory.constant(9, T0 + 15_000, 3_000, 25, 3.7, 50.0, 30.0, 800.0);
        send("P-R", "B-R-3", T0 + 87_000, recoverLong);
        PackSnapshot snap = assessmentService.snapshot("P-R");
        log.info("[用例] 违例滑出窗口且持续恢复超15s -> state={} 时间线={}", snap.state(), snap.timeline());
        assertEquals(HealthState.NORMAL, snap.state());
        // 时间线里应能看到升级与降级的完整可回溯记录
        assertEquals(HealthState.WARNING, snap.timeline().get(0).to());
        assertEquals(HealthState.NORMAL, snap.timeline().get(snap.timeline().size() - 1).to());
    }

    @Test
    void stepwiseBatchesProduceMonotonicTraceableTimeline() {
        // 批次1：45℃ 预警持续 12s -> WARNING
        send("P-M", "B-M-1", T0 + 12_000,
                TelemetryFactory.constant(0, T0, 3_000, 5, 3.7, 50.0, 45.0, 800.0));
        assertEquals(HealthState.WARNING, assessmentService.snapshot("P-M").state());

        // 批次2：升到 61℃（严重过温），3 条满足 critHold=3s -> CRITICAL
        send("P-M", "B-M-2", T0 + 18_000,
                TelemetryFactory.constant(5, T0 + 15_000, 2_000, 3, 3.7, 50.0, 61.0, 800.0));
        assertEquals(HealthState.CRITICAL, assessmentService.snapshot("P-M").state());

        // 批次3：继续快速升温（+3℃/2s=1.5℃/s，温度>50℃）-> THERMAL_RUNAWAY
        send("P-M", "B-M-3", T0 + 24_000,
                TelemetryFactory.ramp(8, T0 + 21_000, 2_000, 3, 3.7, 50.0, 64.0, 3.0, 800.0));
        PackSnapshot snap = assessmentService.snapshot("P-M");
        log.info("[用例] 分批渐进升级 -> state={} 时间线={}", snap.state(), snap.timeline());
        assertEquals(HealthState.THERMAL_RUNAWAY, snap.state());
        assertEquals(HealthState.WARNING, snap.timeline().get(0).to());
        assertEquals(HealthState.CRITICAL, snap.timeline().get(1).to());
        assertEquals(HealthState.THERMAL_RUNAWAY, snap.timeline().get(2).to());
        // 每条迁移都带触发依据
        snap.timeline().forEach(t -> assertTrue(t.reason() != null && !t.reason().isBlank()));
    }

    private List<String> activeKeys(PackSnapshot snap) {
        return snap.faults().stream().filter(f -> f.active()).map(f -> f.fault().name()).toList();
    }
}
