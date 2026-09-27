package com.github.highcumontoa.batterysafetymonitorjava.assess;

import com.github.highcumontoa.batterysafetymonitorjava.alert.AlertService;
import com.github.highcumontoa.batterysafetymonitorjava.alert.AuditLog;
import com.github.highcumontoa.batterysafetymonitorjava.model.AssessmentResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.AuditRecord;
import com.github.highcumontoa.batterysafetymonitorjava.model.HealthState;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 状态评估：基于连续采样窗口计算健康度与热失控风险，驱动单调状态机。
 * <ul>
 *   <li>窗口样本数不足 MIN_WINDOW_SAMPLES 时不做升级判定，避免短暂波动误报；</li>
 *   <li>升级需窗口内超阈值样本占比 >= BREACH_FRACTION；绝缘失效为离散安全故障，立即升级；</li>
 *   <li>降级（恢复）需窗口满且窗内样本全部干净，恢复条件明确；</li>
 *   <li>乱序旧数据无法入窗，因而不会覆盖较新结论；</li>
 *   <li>每次状态迁移写入审计日志，可回溯。</li>
 * </ul>
 */
@Service
public class AssessmentService {

    // ---- 窗口参数 ----
    public static final long WINDOW_MILLIS = 30_000;
    public static final int WINDOW_MAX_SIZE = 120;
    public static final int MIN_WINDOW_SAMPLES = 5;
    /** 升级判定所需的最小超阈值样本占比。 */
    public static final double BREACH_FRACTION = 0.5;

    // ---- 阈值 ----
    public static final double TEMP_ATTENTION_C = 48.0;
    public static final double TEMP_WARNING_C = 55.0;
    public static final double TEMP_CRITICAL_C = 70.0;
    public static final double VOLTAGE_WARN_LOW = 300.0;
    public static final double VOLTAGE_WARN_HIGH = 450.0;
    public static final double VOLTAGE_CRIT_LOW = 250.0;
    public static final double VOLTAGE_CRIT_HIGH = 480.0;
    /** 热失控温升速率阈值（°C/s）。 */
    public static final double TEMP_RISE_RATE_CRITICAL = 2.0;
    public static final double RISK_ATTENTION = 0.3;
    public static final double RISK_WARNING = 0.5;
    public static final double RISK_CRITICAL = 0.8;

    private final AlertService alertService;
    private final AuditLog auditLog;
    private final ConcurrentHashMap<String, PackContext> packs = new ConcurrentHashMap<>();

    public AssessmentService(AlertService alertService, AuditLog auditLog) {
        this.alertService = alertService;
        this.auditLog = auditLog;
    }

    /** 接入一条已受理采样并触发评估。乱序采样不会入窗，也不会改变结论。 */
    public void onSample(TelemetrySample sample) {
        PackContext ctx = packs.computeIfAbsent(sample.getPackId(), id -> new PackContext());
        synchronized (ctx) {
            if (!ctx.window.add(sample)) {
                return; // 乱序/重复时间戳：忽略，不覆盖较新结论
            }
            AssessmentResult result = evaluate(sample.getPackId(), ctx);
            if (result.state() != ctx.state) {
                auditLog.append(new AuditRecord(result.assessedAtMillis(), sample.getPackId(),
                        "STATE_TRANSITION",
                        ctx.state + " -> " + result.state() + " | basis: " + result.basis()));
                ctx.state = result.state();
            }
            ctx.lastAssessment = result;
            alertService.onAssessment(result);
        }
    }

    public Optional<AssessmentResult> currentAssessment(String packId) {
        PackContext ctx = packs.get(packId);
        if (ctx == null) {
            return Optional.empty();
        }
        synchronized (ctx) {
            return Optional.ofNullable(ctx.lastAssessment);
        }
    }

    public HealthState currentState(String packId) {
        PackContext ctx = packs.get(packId);
        return ctx == null ? HealthState.NORMAL : ctx.state;
    }

    // ---- 内部：窗口统计与状态判定 ----

    private AssessmentResult evaluate(String packId, PackContext ctx) {
        List<TelemetrySample> window = ctx.window.snapshot();
        int n = window.size();
        long now = window.get(n - 1).getTimestampMillis();

        double maxTemp = Double.MIN_VALUE;
        long tempWarnCount = 0, tempCritCount = 0, tempAttCount = 0;
        long voltWarnCount = 0, voltCritCount = 0;
        boolean insulationFault = false;
        double tempSum = 0;
        for (TelemetrySample s : window) {
            double t = s.getTemperature();
            double v = s.getVoltage();
            tempSum += t;
            maxTemp = Math.max(maxTemp, t);
            if (t >= TEMP_CRITICAL_C) tempCritCount++;
            else if (t >= TEMP_WARNING_C) tempWarnCount++;
            else if (t >= TEMP_ATTENTION_C) tempAttCount++;
            if (v <= VOLTAGE_CRIT_LOW || v >= VOLTAGE_CRIT_HIGH) voltCritCount++;
            else if (v <= VOLTAGE_WARN_LOW || v >= VOLTAGE_WARN_HIGH) voltWarnCount++;
            if (Boolean.FALSE.equals(s.getInsulationOk())) insulationFault = true;
        }
        double avgTemp = tempSum / n;
        // 温升速率取逐段增量的中位数：对单点尖峰稳健，避免短暂波动误报
        double riseRate = medianStepRiseRate(window);
        // 风险分基于窗口均温而非瞬时峰值，同样为抑制尖峰误报
        double risk = thermalRisk(avgTemp, riseRate);

        StringBuilder basis = new StringBuilder();
        basis.append("window=").append(n)
             .append(", avgTemp=").append(round(avgTemp))
             .append("C, maxTemp=").append(round(maxTemp))
             .append("C, tempRise=").append(round(riseRate))
             .append("C/s, risk=").append(round(risk));

        HealthState candidate = HealthState.NORMAL;
        // 绝缘失效：离散安全故障，立即升级
        if (insulationFault) {
            candidate = HealthState.CRITICAL;
            basis.append("; insulation fault detected");
        }
        // 统计类升级：需窗口足够且超阈值占比达标
        if (n >= MIN_WINDOW_SAMPLES) {
            if (fraction(tempCritCount, n) || fraction(voltCritCount, n)
                    || (risk >= RISK_CRITICAL && riseRate >= TEMP_RISE_RATE_CRITICAL)) {
                if (candidate.ordinal() < HealthState.CRITICAL.ordinal()) {
                    candidate = HealthState.CRITICAL;
                    basis.append("; sustained critical breach (tempCrit=").append(tempCritCount)
                         .append(", voltCrit=").append(voltCritCount).append(")");
                }
            }
            if (candidate.ordinal() < HealthState.WARNING.ordinal()
                    && (fraction(tempWarnCount, n) || fraction(voltWarnCount, n) || risk >= RISK_WARNING)) {
                candidate = HealthState.WARNING;
                basis.append("; sustained warning breach (tempWarn=").append(tempWarnCount)
                     .append(", voltWarn=").append(voltWarnCount).append(")");
            }
            if (candidate.ordinal() < HealthState.ATTENTION.ordinal()
                    && (fraction(tempAttCount, n) || risk >= RISK_ATTENTION)) {
                candidate = HealthState.ATTENTION;
                basis.append("; attention level breach");
            }
        } else {
            basis.append("; window too small for statistical escalation (need ")
                 .append(MIN_WINDOW_SAMPLES).append(")");
        }

        // 单调状态机：升级即时；降级需窗口满且全部干净
        HealthState decided = decideTransition(ctx.state, candidate, window, n, basis);
        double health = healthScore(avgTemp, maxTemp, window, insulationFault);
        return new AssessmentResult(packId, decided, health, risk, n, basis.toString(), now);
    }

    private HealthState decideTransition(HealthState current, HealthState candidate,
                                         List<TelemetrySample> window, int n, StringBuilder basis) {
        if (candidate.ordinal() >= current.ordinal()) {
            return candidate; // 升级或保持
        }
        // 降级：窗口满且窗内无任何超阈值样本才允许恢复
        boolean fullWindow = n >= MIN_WINDOW_SAMPLES;
        boolean allClean = fullWindow && window.stream().allMatch(s ->
                s.getTemperature() < TEMP_ATTENTION_C
                        && s.getVoltage() > VOLTAGE_WARN_LOW && s.getVoltage() < VOLTAGE_WARN_HIGH
                        && !Boolean.FALSE.equals(s.getInsulationOk()));
        if (allClean) {
            basis.append("; recovery: full clean window");
            return candidate;
        }
        basis.append("; recovery suppressed (window not fully clean)");
        return current;
    }

    private static boolean fraction(long count, int n) {
        return count > 0 && (double) count / n >= BREACH_FRACTION;
    }

    /** 逐段温升速率的中位数（°C/s），单点尖峰不会主导结果。 */
    private static double medianStepRiseRate(List<TelemetrySample> window) {
        if (window.size() < 2) {
            return 0.0;
        }
        List<Double> rates = new ArrayList<>(window.size() - 1);
        for (int i = 1; i < window.size(); i++) {
            TelemetrySample prev = window.get(i - 1);
            TelemetrySample cur = window.get(i);
            long dt = cur.getTimestampMillis() - prev.getTimestampMillis();
            if (dt > 0) {
                rates.add((cur.getTemperature() - prev.getTemperature()) / (dt / 1000.0));
            }
        }
        if (rates.isEmpty()) {
            return 0.0;
        }
        rates.sort(Double::compareTo);
        int mid = rates.size() / 2;
        return rates.size() % 2 == 1 ? rates.get(mid) : (rates.get(mid - 1) + rates.get(mid)) / 2.0;
    }

    private static double thermalRisk(double maxTemp, double riseRate) {
        double tempFactor = clamp((maxTemp - 45.0) / (TEMP_CRITICAL_C - 45.0), 0, 1);
        double riseFactor = clamp(riseRate / TEMP_RISE_RATE_CRITICAL, 0, 1);
        return 0.6 * tempFactor + 0.4 * riseFactor;
    }

    private static double healthScore(double avgTemp, double maxTemp, List<TelemetrySample> window,
                                      boolean insulationFault) {
        double score = 100.0;
        if (maxTemp > 45.0) {
            score -= (maxTemp - 45.0) * 2.0;
        }
        double avgVolt = window.stream().mapToDouble(TelemetrySample::getVoltage).average().orElse(400.0);
        if (avgVolt < VOLTAGE_WARN_LOW || avgVolt > VOLTAGE_WARN_HIGH) {
            score -= 15.0;
        }
        if (insulationFault) {
            score -= 40.0;
        }
        return clamp(score, 0, 100);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 单包评估上下文：窗口 + 当前状态 + 最近结论。 */
    private static final class PackContext {
        final SlidingWindow window = new SlidingWindow(WINDOW_MILLIS, WINDOW_MAX_SIZE);
        volatile HealthState state = HealthState.NORMAL;
        AssessmentResult lastAssessment;
    }
}
