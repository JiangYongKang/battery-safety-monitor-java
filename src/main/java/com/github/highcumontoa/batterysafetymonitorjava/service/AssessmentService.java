package com.github.highcumontoa.batterysafetymonitorjava.service;

import com.github.highcumontoa.batterysafetymonitorjava.config.MonitorProperties;
import com.github.highcumontoa.batterysafetymonitorjava.model.ActionResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.AlarmEvent;
import com.github.highcumontoa.batterysafetymonitorjava.model.AssessmentResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.FaultKey;
import com.github.highcumontoa.batterysafetymonitorjava.model.FaultSnapshot;
import com.github.highcumontoa.batterysafetymonitorjava.model.HealthState;
import com.github.highcumontoa.batterysafetymonitorjava.model.PackSnapshot;
import com.github.highcumontoa.batterysafetymonitorjava.model.StateTransition;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetryBatch;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 连续采样窗口评估服务。
 *
 * 规则（均可回溯、可解释）：
 * 1. 窗口：保留最近 window.sizeMs 的采样；故障成立需要连续违例后缀满足
 *    "样本数 ≥ minSamples 且持续时间 ≥ 对应等级 hold"，短暂波动/窗口不足不触发；
 * 2. 升级立即生效；降级必须在故障条件持续消失 recoveryHoldMs 后才允许，状态不抖动；
 * 3. 热失控：窗口温升速率 ≥ 阈值且最新温度 ≥ 基准温度才构成违例；
 * 4. 后到旧数据在接入阶段已被拒绝；评估时间以最新采样时间戳为准（模拟时钟，结论确定）；
 * 5. 评估与告警/处置在包级同一把锁内完成，并发/重复触发不会产生重复处置。
 */
@Service
public class AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);
    private static final int WINDOW_HARD_CAP = 1000;

    private final MonitorProperties props;
    private final PackRegistry registry;
    private final AlarmService alarmService;

    public AssessmentService(MonitorProperties props,
                             PackRegistry registry,
                             AlarmService alarmService) {
        this.props = props;
        this.registry = registry;
        this.alarmService = alarmService;
    }

    /** 供接入链路调用：调用方已持有 state.lock。 */
    public AssessmentResult evaluateUnderLock(PackRegistry.PackState state, TelemetryBatch batch) {
        return doEvaluate(state);
    }

    /** 外部入口（手动触发）：按包加锁；旧批次不产生新结论。 */
    public AssessmentResult evaluate(String packId, TelemetryBatch batch) {
        PackRegistry.PackState state = registry.stateFor(packId);
        synchronized (state.lock) {
            if (batch != null && batch.sentAtMs() < state.lastBatchSentAtMs) {
                log.warn("[评估] 丢弃后到旧批次 packId={} sentAtMs={} < last={}",
                        packId, batch.sentAtMs(), state.lastBatchSentAtMs);
                return new AssessmentResult(packId, System.currentTimeMillis(), state.lastAcceptedSampleTs,
                        state.currentState, state.healthScore, true,
                        activeSnapshots(state), List.of(), List.of());
            }
            return doEvaluate(state);
        }
    }

    /** 对全部已知电池包执行一次评估（用于时间推进后的恢复判定）。 */
    public List<AssessmentResult> evaluateAll() {
        List<AssessmentResult> results = new ArrayList<>();
        for (String packId : registry.packIds()) {
            PackRegistry.PackState state = registry.stateFor(packId);
            synchronized (state.lock) {
                results.add(doEvaluate(state));
            }
        }
        return results;
    }

    public PackSnapshot snapshot(String packId) {
        PackRegistry.PackState state = registry.stateFor(packId);
        synchronized (state.lock) {
            return new PackSnapshot(packId, state.currentState, state.healthScore,
                    state.lastAcceptedAtMs, state.lastBatchSentAtMs, state.lastEvaluatedSampleTs,
                    allSnapshots(state), List.copyOf(state.timeline));
        }
    }

    // ------------------------------------------------------------------

    private AssessmentResult doEvaluate(PackRegistry.PackState state) {
        if (state.window.isEmpty()) {
            return new AssessmentResult(state.packId, 0L, state.lastEvaluatedSampleTs,
                    state.currentState, state.healthScore, false,
                    List.of(), List.of(), List.of());
        }
        long now = state.window.peekLast().timestampMs();
        trimWindow(state, now);

        List<TelemetrySample> window = List.copyOf(state.window);
        TelemetrySample latest = window.get(window.size() - 1);
        List<AlarmEvent> newAlarms = new ArrayList<>();
        List<ActionResult> newActions = new ArrayList<>();

        EnumMap<FaultKey, Boolean> violatingNow = evaluatePredicates(window, latest);

        for (FaultKey key : FaultKey.values()) {
            PackRegistry.FaultRuntime rt = state.faults.get(key);
            boolean violating = Boolean.TRUE.equals(violatingNow.get(key));

            if (violating) {
                long[] suffix = consecutiveViolatingSuffix(window, violatingNow, key);
                int count = (int) suffix[0];
                long firstTs = suffix[1];
                long duration = now - firstTs;
                long hold = holdFor(key);
                String evidence = buildEvidence(key, latest, count, duration, hold);

                rt.violating = true;
                rt.firstViolationMs = firstTs;
                rt.lastViolationMs = now;
                rt.recoverSinceMs = Long.MIN_VALUE;
                rt.evidence = evidence;

                if (!rt.active && count >= props.getWindow().getMinSamples() && duration >= hold) {
                    rt.active = true;
                    log.info("[评估] 故障成立 packId={} fault={} 连续样本={} 持续={}ms 阈值要求={}ms 依据={}",
                            state.packId, key, count, duration, hold, evidence);
                    newAlarms.addAll(alarmService.maybeAlarm(state.packId, key, now, evidence));
                    newActions.addAll(alarmService.executeActions(state.packId, key, now, evidence));
                } else if (!rt.active) {
                    log.info("[评估] 候选故障未成立(窗口/持续时间不足) packId={} fault={} 连续样本={}/{} 持续={}ms/{}ms",
                            state.packId, key, count, props.getWindow().getMinSamples(), duration, hold);
                }
            } else {
                boolean wasActive = rt.active;
                rt.markNonViolation(now);
                // 恢复保持从"窗口内最后一次违例"起算（而非本次评估时刻），
                // 这样稀疏上报、旧违例随窗口滑出的时间也被正确计入，结论与上报频率无关
                long sinceLastViolation = rt.lastViolationMs == Long.MIN_VALUE
                        ? Long.MAX_VALUE : now - rt.lastViolationMs;
                if (wasActive && sinceLastViolation >= props.getDuration().getRecoveryHoldMs()) {
                    rt.active = false;
                    rt.clearedAtMs = now;
                    alarmService.markRecovered(state.packId, key, now);
                    log.info("[评估] 故障恢复并清除 packId={} fault={} 距最后违例={}ms",
                            state.packId, key, sinceLastViolation);
                }
            }
        }

        HealthState newState = deriveState(state);
        if (newState != state.currentState) {
            boolean upgrade = newState.level() > state.currentState.level();
            String reason = (upgrade ? "故障升级: " : "持续恢复后降级: ") + describeActive(state);
            state.appendTransition(new StateTransition(now, state.currentState, newState, reason));
            log.info("[评估] 状态迁移 packId={} {} -> {} 依据={}", state.packId, state.currentState, newState, reason);
            state.currentState = newState;
        }
        state.healthScore = computeHealthScore(state);
        state.lastEvaluatedSampleTs = now;

        return new AssessmentResult(state.packId, now, now, state.currentState, state.healthScore,
                false, activeSnapshots(state), List.copyOf(newAlarms), List.copyOf(newActions));
    }

    private void trimWindow(PackRegistry.PackState state, long now) {
        long cutoff = now - props.getWindow().getSizeMs();
        while (state.window.size() > 1 && state.window.peekFirst().timestampMs() < cutoff) {
            state.window.pollFirst();
        }
        while (state.window.size() > WINDOW_HARD_CAP) {
            state.window.pollFirst();
        }
    }

    /** 返回各故障在最新采样上的点违例判定（热失控为窗口级判定）。 */
    private EnumMap<FaultKey, Boolean> evaluatePredicates(List<TelemetrySample> w, TelemetrySample s) {
        MonitorProperties.Thresholds t = props.getThresholds();
        EnumMap<FaultKey, Boolean> m = new EnumMap<>(FaultKey.class);
        m.put(FaultKey.TEMP_WARN, s.tempC() >= t.getTempWarnC());
        m.put(FaultKey.TEMP_CRIT, s.tempC() >= t.getTempCritC());
        m.put(FaultKey.OVERVOLTAGE_WARN, s.voltageV() >= t.getOverVWarn());
        m.put(FaultKey.OVERVOLTAGE_CRIT, s.voltageV() >= t.getOverVCrit());
        m.put(FaultKey.UNDERVOLTAGE_WARN, s.voltageV() <= t.getUnderVWarn());
        m.put(FaultKey.UNDERVOLTAGE_CRIT, s.voltageV() <= t.getUnderVCrit());
        m.put(FaultKey.OVERCURRENT_WARN, Math.abs(s.currentA()) >= t.getOverAWarn());
        m.put(FaultKey.OVERCURRENT_CRIT, Math.abs(s.currentA()) >= t.getOverACrit());
        boolean insWarn = s.insulationKOhm() != null && s.insulationKOhm() <= t.getInsulationWarnK();
        boolean insCrit = s.insulationKOhm() != null && s.insulationKOhm() <= t.getInsulationCritK();
        m.put(FaultKey.INSULATION_WARN, insWarn);
        m.put(FaultKey.INSULATION_CRIT, insCrit);
        m.put(FaultKey.THERMAL_RUNAWAY, thermalRunawayViolating(w, s));
        return m;
    }

    /**
     * 热失控违例：最新温度 ≥ 基准温度，且窗口首尾温升速率 ≥ 阈值（℃/s）。
     * 窗口样本不足或时间跨度为 0 时不构成违例。
     */
    private boolean thermalRunawayViolating(List<TelemetrySample> w, TelemetrySample latest) {
        MonitorProperties.Thresholds t = props.getThresholds();
        if (latest.tempC() < t.getThermalTempBaseC()) {
            return false;
        }
        if (w.size() < props.getWindow().getMinSamples()) {
            return false;
        }
        TelemetrySample first = w.get(0);
        long dtMs = latest.timestampMs() - first.timestampMs();
        if (dtMs <= 0) {
            return false;
        }
        double risePerSec = (latest.tempC() - first.tempC()) * 1000.0 / dtMs;
        return risePerSec >= t.getThermalRisePerSec();
    }

    /** 返回 [连续违例后缀长度, 后缀首个时间戳]。 */
    private long[] consecutiveViolatingSuffix(List<TelemetrySample> w,
                                             EnumMap<FaultKey, Boolean> latestMap,
                                             FaultKey key) {
        long count = 0;
        long firstTs = w.get(w.size() - 1).timestampMs();
        for (int i = w.size() - 1; i >= 0; i--) {
            TelemetrySample s = w.get(i);
            if (pointViolates(key, s)) {
                count++;
                firstTs = s.timestampMs();
            } else {
                break;
            }
        }
        return new long[]{count, firstTs};
    }

    private boolean pointViolates(FaultKey key, TelemetrySample s) {
        MonitorProperties.Thresholds t = props.getThresholds();
        return switch (key) {
            case TEMP_WARN -> s.tempC() >= t.getTempWarnC();
            case TEMP_CRIT -> s.tempC() >= t.getTempCritC();
            case OVERVOLTAGE_WARN -> s.voltageV() >= t.getOverVWarn();
            case OVERVOLTAGE_CRIT -> s.voltageV() >= t.getOverVCrit();
            case UNDERVOLTAGE_WARN -> s.voltageV() <= t.getUnderVWarn();
            case UNDERVOLTAGE_CRIT -> s.voltageV() <= t.getUnderVCrit();
            case OVERCURRENT_WARN -> Math.abs(s.currentA()) >= t.getOverAWarn();
            case OVERCURRENT_CRIT -> Math.abs(s.currentA()) >= t.getOverACrit();
            case INSULATION_WARN -> s.insulationKOhm() != null && s.insulationKOhm() <= t.getInsulationWarnK();
            case INSULATION_CRIT -> s.insulationKOhm() != null && s.insulationKOhm() <= t.getInsulationCritK();
            case THERMAL_RUNAWAY -> s.tempC() >= t.getThermalTempBaseC();
        };
    }

    private long holdFor(FaultKey key) {
        return key.level().severity() >= com.github.highcumontoa.batterysafetymonitorjava.model.AlarmLevel.CRITICAL.severity()
                ? props.getDuration().getCritHoldMs()
                : props.getDuration().getWarnHoldMs();
    }

    private String buildEvidence(FaultKey key, TelemetrySample s, int count, long duration, long hold) {
        String values = "V=" + s.voltageV() + "V, I=" + s.currentA() + "A, T=" + s.tempC()
                + "℃, R=" + (s.insulationKOhm() == null ? "n/a(v1)" : s.insulationKOhm() + "kΩ");
        return key.display() + " {" + values + "}; 连续违例样本=" + count
                + ", 持续=" + duration + "ms, 触发要求=" + hold + "ms, ts=" + s.timestampMs();
    }

    private HealthState deriveState(PackRegistry.PackState state) {
        boolean critical = false;
        boolean warn = false;
        for (Map.Entry<FaultKey, PackRegistry.FaultRuntime> e : state.faults.entrySet()) {
            if (!e.getValue().active) {
                continue;
            }
            FaultKey k = e.getKey();
            if (k == FaultKey.THERMAL_RUNAWAY) {
                return HealthState.THERMAL_RUNAWAY;
            }
            if (k.level().severity() >= com.github.highcumontoa.batterysafetymonitorjava.model.AlarmLevel.CRITICAL.severity()) {
                critical = true;
            } else {
                warn = true;
            }
        }
        if (critical) {
            return HealthState.CRITICAL;
        }
        if (warn) {
            return HealthState.WARNING;
        }
        return HealthState.NORMAL;
    }

    private String describeActive(PackRegistry.PackState state) {
        List<String> act = new ArrayList<>();
        state.faults.forEach((k, v) -> {
            if (v.active) {
                act.add(k.name());
            }
        });
        return act.isEmpty() ? "无活动故障" : act.toString();
    }

    private int computeHealthScore(PackRegistry.PackState state) {
        int score = 100;
        for (Map.Entry<FaultKey, PackRegistry.FaultRuntime> e : state.faults.entrySet()) {
            if (!e.getValue().active) {
                continue;
            }
            FaultKey k = e.getKey();
            if (k == FaultKey.THERMAL_RUNAWAY) {
                score -= 40;
            } else if (k.level().severity() >= com.github.highcumontoa.batterysafetymonitorjava.model.AlarmLevel.CRITICAL.severity()) {
                score -= 20;
            } else {
                score -= 8;
            }
        }
        return Math.max(0, Math.min(100, score));
    }

    private List<FaultSnapshot> activeSnapshots(PackRegistry.PackState state) {
        List<FaultSnapshot> list = new ArrayList<>();
        state.faults.forEach((k, rt) -> {
            if (rt.active) {
                list.add(toSnapshot(k, rt));
            }
        });
        return list;
    }

    private List<FaultSnapshot> allSnapshots(PackRegistry.PackState state) {
        List<FaultSnapshot> list = new ArrayList<>();
        state.faults.forEach((k, rt) -> list.add(toSnapshot(k, rt)));
        return list;
    }

    private FaultSnapshot toSnapshot(FaultKey k, PackRegistry.FaultRuntime rt) {
        return new FaultSnapshot(k, rt.active, rt.firstViolationMs, rt.lastViolationMs,
                rt.clearedAtMs, rt.evidence);
    }
}
