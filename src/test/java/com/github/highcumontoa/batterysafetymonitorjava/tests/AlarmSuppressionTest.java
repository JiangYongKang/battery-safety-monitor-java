package com.github.highcumontoa.batterysafetymonitorjava.tests;

import com.github.highcumontoa.batterysafetymonitorjava.model.ActionResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.AlarmActionType;
import com.github.highcumontoa.batterysafetymonitorjava.model.AlarmEvent;
import com.github.highcumontoa.batterysafetymonitorjava.model.HealthState;
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

import java.util.List;

import static com.github.highcumontoa.batterysafetymonitorjava.tests.support.TelemetryFactory.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警分级、抑制窗口去重、恢复后重新触发、处置动作审计。
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AlarmSuppressionTest {

    private static final Logger log = LoggerFactory.getLogger(AlarmSuppressionTest.class);

    @Autowired
    private IngestService ingestService;
    @Autowired
    private AssessmentService assessmentService;
    @Autowired
    private AlarmService alarmService;

    private void send(String pack, String batch, long sentAt, List<TelemetryFactory.Sample> samples) {
        ingestService.ingest(TelemetryFactory.batch(batch, pack, "v2", sentAt, samples));
    }

    @Test
    void warnTriggersOnceAndActionsAreAuditable() {
        send("P-A", "B-A-1", T0 + 12_000,
                TelemetryFactory.constant(0, T0, 3_000, 5, 3.7, 50.0, 50.0, 800.0));

        List<AlarmEvent> alarms = alarmService.alarms("P-A");
        List<ActionResult> actions = alarmService.actions("P-A");
        log.info("[用例] WARN触发 -> 告警数={} 告警={} 动作={}", alarms.size(), alarms,
                actions.stream().map(a -> a.action().name()).toList());
        assertEquals(1, alarms.size());
        // WARN -> LOG_ALERT + DASHBOARD_FLAG
        assertEquals(2, actions.size());
        assertTrue(actions.stream().anyMatch(a -> a.action() == AlarmActionType.LOG_ALERT));
        assertTrue(actions.stream().anyMatch(a -> a.action() == AlarmActionType.DASHBOARD_FLAG));
        // 每条审计记录必须包含触发依据
        actions.forEach(a -> assertTrue(a.triggerEvidence().contains("连续违例样本")));
    }

    @Test
    void criticalFaultTriggersRelayCutoff() {
        // 62℃，3 条 ×2s 满足 critHold 3s
        send("P-CR", "B-CR-1", T0 + 4_000,
                TelemetryFactory.constant(0, T0, 2_000, 3, 3.7, 50.0, 62.0, 800.0));
        List<ActionResult> actions = alarmService.actions("P-CR");
        log.info("[用例] CRITICAL触发 -> state={} 动作={}",
                assessmentService.snapshot("P-CR").state(),
                actions.stream().map(ActionResult::action).toList());
        assertEquals(HealthState.CRITICAL, assessmentService.snapshot("P-CR").state());
        assertTrue(actions.stream().anyMatch(a -> a.action() == AlarmActionType.RELAY_CUTOFF));
    }

    @Test
    void repeatedTriggerWithinSuppressionWindowRaisesOnlyOneAlarm() {
        // 故障在 +12s 成立并告警；后续批次故障持续、评估被反复触发
        send("P-S", "B-S-1", T0 + 12_000,
                TelemetryFactory.constant(0, T0, 3_000, 5, 3.7, 50.0, 50.0, 800.0));
        assertEquals(1, alarmService.alarms("P-S").size());

        send("P-S", "B-S-2", T0 + 30_000,
                TelemetryFactory.constant(5, T0 + 15_000, 3_000, 6, 3.7, 50.0, 50.0, 800.0));
        send("P-S", "B-S-3", T0 + 45_000,
                TelemetryFactory.constant(11, T0 + 33_000, 3_000, 5, 3.7, 50.0, 50.0, 800.0));
        List<AlarmEvent> alarms = alarmService.alarms("P-S");
        log.info("[用例] 抑制窗口内重复触发 -> 外部告警数={} (故障持续，期望1) 告警时间={}",
                alarms.size(), alarms.stream().map(AlarmEvent::triggeredAtMs).toList());
        // 首次 12s，抑制窗口 60s：45s 内的重复触发全部被抑制
        assertEquals(1, alarms.size());
    }

    @Test
    void alarmCanTriggerAgainAfterRecovery() {
        // 第一次故障
        send("P-RT", "B-1", T0 + 12_000,
                TelemetryFactory.constant(0, T0, 3_000, 5, 3.7, 50.0, 50.0, 800.0));
        assertEquals(1, alarmService.alarms("P-RT").size());

        // 长时间恢复：正常样本从 +15s 覆盖到 +195s，旧违例(最晚+12s)早已滑出 60s 窗口并保持>15s
        send("P-RT", "B-2", T0 + 195_000,
                TelemetryFactory.constant(5, T0 + 15_000, 3_000, 61, 3.7, 50.0, 30.0, 800.0));
        assertEquals(HealthState.NORMAL, assessmentService.snapshot("P-RT").state());
        log.info("[用例] 已恢复，告警计数={}", alarmService.alarms("P-RT").size());

        // 再次发生同类故障（新的时间基准，满足持续 12s）-> 必须能重新外部告警
        send("P-RT", "B-3", T0 + 220_000,
                TelemetryFactory.constant(70, T0 + 205_000, 3_000, 5, 3.7, 50.0, 50.0, 800.0));
        List<AlarmEvent> alarms = alarmService.alarms("P-RT");
        log.info("[用例] 恢复后重新触发 -> 外部告警数={} 时间={}",
                alarms.size(), alarms.stream().map(AlarmEvent::triggeredAtMs).toList());
        assertEquals(2, alarms.size());
    }
}
