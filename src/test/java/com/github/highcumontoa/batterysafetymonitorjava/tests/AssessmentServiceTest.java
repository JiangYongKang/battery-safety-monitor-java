package com.github.highcumontoa.batterysafetymonitorjava.tests;

import com.github.highcumontoa.batterysafetymonitorjava.alert.AlertService;
import com.github.highcumontoa.batterysafetymonitorjava.alert.AuditLog;
import com.github.highcumontoa.batterysafetymonitorjava.assess.AssessmentService;
import com.github.highcumontoa.batterysafetymonitorjava.model.AssessmentResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.HealthState;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 状态评估：窗口不足不误报、短暂波动不升级、单调迁移、明确恢复条件、可回溯。 */
class AssessmentServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AssessmentServiceTest.class);
    private static final String PACK = "PACK-ASSESS";
    private static final long BASE_TS = 1_000_000L;

    private AssessmentService assessmentService;
    private AuditLog auditLog;

    @BeforeEach
    void setUp() {
        auditLog = new AuditLog();
        assessmentService = new AssessmentService(new AlertService(auditLog), auditLog);
    }

    private TelemetrySample sample(long seq, double temp, double voltage, Boolean insulationOk) {
        TelemetrySample s = new TelemetrySample();
        s.setPackId(PACK);
        s.setSequenceNumber(seq);
        s.setTimestampMillis(BASE_TS + seq * 1000L);
        s.setVoltage(voltage);
        s.setCurrent(50.0);
        s.setTemperature(temp);
        s.setInsulationOk(insulationOk);
        return s;
    }

    private HealthState feed(long fromSeq, int count, double temp, double voltage, Boolean insulation) {
        for (int i = 0; i < count; i++) {
            long seq = fromSeq + i;
            assessmentService.onSample(sample(seq, temp, voltage, insulation));
        }
        HealthState state = assessmentService.currentState(PACK);
        AssessmentResult r = assessmentService.currentAssessment(PACK).orElseThrow();
        log.info("FEED seq=[{}..{}] temp={}C volt={}V insulation={} -> state={} basis={}",
                fromSeq, fromSeq + count - 1, temp, voltage, insulation, state, r.basis());
        return state;
    }

    @Test
    void insufficientWindowDoesNotEscalate() {
        // 4 条 80°C（< MIN_WINDOW_SAMPLES=5）：统计升级不触发
        HealthState state = feed(1, AssessmentService.MIN_WINDOW_SAMPLES - 1, 80.0, 400.0, true);
        log.info("JUDGE window={} < {} -> expect NORMAL", AssessmentService.MIN_WINDOW_SAMPLES - 1,
                AssessmentService.MIN_WINDOW_SAMPLES);
        assertEquals(HealthState.NORMAL, state);
    }

    @Test
    void briefFluctuationDoesNotEscalate() {
        // 9 条正常 + 1 条尖峰 90°C：超阈值占比 10% < 50%，不升级
        feed(1, 5, 35.0, 400.0, true);
        feed(6, 1, 90.0, 400.0, true);
        HealthState state = feed(7, 4, 35.0, 400.0, true);
        log.info("JUDGE breach fraction=1/10 < {} -> expect NORMAL", AssessmentService.BREACH_FRACTION);
        assertEquals(HealthState.NORMAL, state);
    }

    @Test
    void sustainedBreachEscalatesMonotonically() {
        HealthState s1 = feed(1, 6, 60.0, 400.0, true);
        assertEquals(HealthState.WARNING, s1);
        // 持续 75°C：窗口中临界样本占比逐步过半 -> CRITICAL
        HealthState s2 = feed(7, 6, 75.0, 400.0, true);
        log.info("JUDGE escalation path NORMAL->WARNING->CRITICAL, monotonic ordinal");
        assertEquals(HealthState.CRITICAL, s2);
        assertTrue(s2.ordinal() > s1.ordinal());
    }

    @Test
    void thresholdBoundaryAtExactWarningTemp() {
        // 恰好 55°C（含边界）：>= 55 计入超阈 -> WARNING
        HealthState state = feed(1, 6, AssessmentService.TEMP_WARNING_C, 400.0, true);
        log.info("JUDGE temp={}C == TEMP_WARNING_C boundary -> expect WARNING",
                AssessmentService.TEMP_WARNING_C);
        assertEquals(HealthState.WARNING, state);
    }

    @Test
    void justBelowThresholdStaysBelow() {
        // 54.9°C 持续：不达 55 -> 最多 ATTENTION
        HealthState state = feed(1, 8, AssessmentService.TEMP_WARNING_C - 0.1, 400.0, true);
        log.info("JUDGE temp={}C < TEMP_WARNING_C -> expect ATTENTION (not WARNING)",
                AssessmentService.TEMP_WARNING_C - 0.1);
        assertEquals(HealthState.ATTENTION, state);
    }

    @Test
    void insulationFaultEscalatesImmediately() {
        feed(1, 3, 35.0, 400.0, true);
        HealthState state = feed(4, 1, 35.0, 400.0, false);
        log.info("JUDGE insulationOk=false -> immediate CRITICAL regardless of window size");
        assertEquals(HealthState.CRITICAL, state);
    }

    @Test
    void recoveryRequiresFullCleanWindow() {
        feed(1, 6, 60.0, 400.0, true);
        assertEquals(HealthState.WARNING, assessmentService.currentState(PACK));
        // 仅 3 条干净样本：窗口未清干净，不恢复
        HealthState s1 = feed(7, 3, 35.0, 400.0, true);
        log.info("JUDGE partial clean window -> recovery suppressed, still WARNING");
        assertEquals(HealthState.WARNING, s1);
        // 继续送干净样本直到旧样本全部滑出窗口（30s 窗口）
        HealthState s2 = feed(10, 30, 35.0, 400.0, true);
        log.info("JUDGE full clean window -> recovery to NORMAL");
        assertEquals(HealthState.NORMAL, s2);
    }

    @Test
    void stateTransitionsAreAudited() {
        feed(1, 6, 60.0, 400.0, true);
        long transitions = auditLog.recordsFor(PACK).stream()
                .filter(r -> r.eventType().equals("STATE_TRANSITION")).count();
        log.info("JUDGE audit STATE_TRANSITION count={} (expect >=1, NORMAL->WARNING)", transitions);
        assertTrue(transitions >= 1);
        assertTrue(auditLog.recordsFor(PACK).stream()
                .anyMatch(r -> r.detail().contains("NORMAL -> WARNING")));
    }
}
