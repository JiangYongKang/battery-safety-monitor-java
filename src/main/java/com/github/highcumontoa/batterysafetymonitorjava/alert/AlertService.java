package com.github.highcumontoa.batterysafetymonitorjava.alert;

import com.github.highcumontoa.batterysafetymonitorjava.model.AlertEvent;
import com.github.highcumontoa.batterysafetymonitorjava.model.AlertLevel;
import com.github.highcumontoa.batterysafetymonitorjava.model.AssessmentResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.AuditRecord;
import com.github.highcumontoa.batterysafetymonitorjava.model.DisposalAction;
import com.github.highcumontoa.batterysafetymonitorjava.model.HealthState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 分级告警与处置。
 * <ul>
 *   <li>触发条件 = 状态级别 + 持续时间：CRITICAL 立即，WARNING 需持续 2s，ATTENTION 需持续 3s；</li>
 *   <li>同一故障（packId + 级别）在一次故障期内只告警一次，且抑制窗口
 *       {@link #SUPPRESSION_WINDOW_MILLIS} 内绝不重复；</li>
 *   <li>重新触发条件明确：状态先恢复到 NORMAL（故障期结束），再次越限并满足持续时间；</li>
 *   <li>旧结论（assessedAtMillis 不晚于已处理水位）直接忽略，不覆盖新结论；</li>
 *   <li>告警、抑制、处置、恢复全部写入审计日志。</li>
 * </ul>
 */
@Service
public class AlertService {

    public static final long SUPPRESSION_WINDOW_MILLIS = 60_000;
    public static final long WARNING_MIN_DURATION_MILLIS = 2_000;
    public static final long ATTENTION_MIN_DURATION_MILLIS = 3_000;

    private final AuditLog auditLog;
    private final CopyOnWriteArrayList<AlertEvent> alerts = new CopyOnWriteArrayList<>();
    private final Map<String, PackAlertContext> contexts = new ConcurrentHashMap<>();

    public AlertService(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    public void onAssessment(AssessmentResult result) {
        PackAlertContext ctx = contexts.computeIfAbsent(result.packId(), id -> new PackAlertContext());
        synchronized (ctx) {
            // 旧数据防护：不处理不晚于已处理水位的结论
            if (result.assessedAtMillis() <= ctx.lastProcessedAt) {
                return;
            }
            ctx.lastProcessedAt = result.assessedAtMillis();

            HealthState state = result.state();
            if (state == HealthState.NORMAL) {
                handleRecovery(result, ctx);
                ctx.pendingSince = 0;
                ctx.pendingState = null;
                return;
            }

            long now = result.assessedAtMillis();
            if (ctx.pendingState != state) {
                ctx.pendingState = state;
                ctx.pendingSince = now;
            }
            long elapsed = now - ctx.pendingSince;
            if (elapsed < minDurationFor(state)) {
                return; // 持续时间不足，不触发
            }
            maybeAlert(result, ctx, state, elapsed);
        }
    }

    private void maybeAlert(AssessmentResult result, PackAlertContext ctx, HealthState state, long elapsed) {
        String faultKey = result.packId() + ":" + state;
        long now = result.assessedAtMillis();

        if (ctx.alertedFaultKeys.contains(faultKey)) {
            // 同一故障期内：抑制窗口内只告警一次；窗口外也不重复（同一故障期）
            audit(result.packId(), now, "ALERT_SUPPRESSED",
                    "faultKey=" + faultKey + " already alerted in current episode");
            return;
        }
        Long lastAlertAt = ctx.lastAlertAtByFaultKey.get(faultKey);
        if (lastAlertAt != null && now - lastAlertAt < SUPPRESSION_WINDOW_MILLIS) {
            audit(result.packId(), now, "ALERT_SUPPRESSED",
                    "faultKey=" + faultKey + " within suppression window " + SUPPRESSION_WINDOW_MILLIS + "ms");
            return;
        }

        AlertLevel level = levelFor(state);
        DisposalAction action = actionFor(state);
        String basis = "state=" + state + " sustained " + elapsed + "ms | " + result.basis();
        AlertEvent event = new AlertEvent(UUID.randomUUID().toString(), result.packId(), faultKey,
                level, action, basis, now);
        alerts.add(event);
        ctx.alertedFaultKeys.add(faultKey);
        ctx.lastAlertAtByFaultKey.put(faultKey, now);
        audit(result.packId(), now, "ALERT_TRIGGERED",
                "faultKey=" + faultKey + " level=" + level + " basis: " + basis);
        executeDisposal(result.packId(), action, basis, now);
    }

    private void handleRecovery(AssessmentResult result, PackAlertContext ctx) {
        if (!ctx.alertedFaultKeys.isEmpty()) {
            audit(result.packId(), result.assessedAtMillis(), "RECOVERY",
                    "state back to NORMAL, fault episode closed, keys=" + ctx.alertedFaultKeys);
        }
        // 故障期结束：清除告警标记，允许恢复后重新触发
        ctx.alertedFaultKeys.clear();
    }

    private void executeDisposal(String packId, DisposalAction action, String basis, long now) {
        // 本地模拟处置：不操作真实设备，仅记录可审计结果
        audit(packId, now, "DISPOSAL_EXECUTED", "action=" + action + " | trigger basis: " + basis);
    }

    private void audit(String packId, long at, String type, String detail) {
        auditLog.append(new AuditRecord(at, packId, type, detail));
    }

    private static long minDurationFor(HealthState state) {
        return switch (state) {
            case CRITICAL -> 0;
            case WARNING -> WARNING_MIN_DURATION_MILLIS;
            case ATTENTION -> ATTENTION_MIN_DURATION_MILLIS;
            case NORMAL -> Long.MAX_VALUE;
        };
    }

    private static AlertLevel levelFor(HealthState state) {
        return switch (state) {
            case ATTENTION -> AlertLevel.INFO;
            case WARNING -> AlertLevel.WARN;
            case CRITICAL -> AlertLevel.CRITICAL;
            case NORMAL -> AlertLevel.INFO;
        };
    }

    private static DisposalAction actionFor(HealthState state) {
        return switch (state) {
            case ATTENTION -> DisposalAction.NOTIFY;
            case WARNING -> DisposalAction.LIMIT_POWER;
            case CRITICAL -> DisposalAction.EMERGENCY_STOP;
            case NORMAL -> DisposalAction.NONE;
        };
    }

    public List<AlertEvent> alerts() {
        return List.copyOf(alerts);
    }

    public List<AlertEvent> alertsFor(String packId) {
        return alerts.stream().filter(a -> a.packId().equals(packId)).toList();
    }

    /** 单包告警上下文。 */
    private static final class PackAlertContext {
        long lastProcessedAt = Long.MIN_VALUE;
        HealthState pendingState;
        long pendingSince;
        final java.util.Set<String> alertedFaultKeys = ConcurrentHashMap.newKeySet();
        final Map<String, Long> lastAlertAtByFaultKey = new ConcurrentHashMap<>();
    }
}
