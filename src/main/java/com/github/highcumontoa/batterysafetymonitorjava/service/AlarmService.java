package com.github.highcumontoa.batterysafetymonitorjava.service;

import com.github.highcumontoa.batterysafetymonitorjava.config.MonitorProperties;
import com.github.highcumontoa.batterysafetymonitorjava.model.ActionResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.AlarmActionType;
import com.github.highcumontoa.batterysafetymonitorjava.model.AlarmEvent;
import com.github.highcumontoa.batterysafetymonitorjava.model.AlarmLevel;
import com.github.highcumontoa.batterysafetymonitorjava.model.FaultKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分级告警与处置服务（全部为本地模拟，无外部依赖）。
 *
 * 抑制与重触发策略（对每个 电池包+故障 独立生效）：
 * - 故障活动期间，距上次外部告警不超过 suppression.windowMs 时，重复触发只记录审计、不再外部告警；
 * - 超过抑制窗口且故障仍活动（持续评估被重复触发）时，允许再产生一次外部告警（周期性提醒）；
 * - 故障必须先恢复（markRecovered），下一次重新成立才视为新一轮告警；
 * - WARN→CRITICAL 升级属于更高等级新结论，允许立即产生 CRITICAL 告警。
 *
 * 处置动作：
 * - INFO：LOG_ALERT；WARN：LOG_ALERT + DASHBOARD_FLAG；CRITICAL：追加 RELAY_CUTOFF（模拟切断继电器）。
 * 每次动作都返回 {@link ActionResult}，包含触发依据与上下文，可审计。
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);
    private static final int HISTORY_CAPACITY = 500;

    private final MonitorProperties props;

    /** packId -> faultKey -> 抑制状态 */
    private final ConcurrentHashMap<String, EnumMap<FaultKey, SuppressionState>> suppressions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<AlarmEvent>> alarmHistory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ActionResult>> actionHistory = new ConcurrentHashMap<>();
    private final AtomicLong actionSeq = new AtomicLong();

    public AlarmService(MonitorProperties props) {
        this.props = props;
    }

    /**
     * 故障成立时由评估服务在包锁内调用。
     * @return 新产生的外部告警（被抑制时为空列表）
     */
    public List<AlarmEvent> maybeAlarm(String packId, FaultKey fault, long nowMs, String evidence) {
        SuppressionState st = stateFor(packId, fault);
        synchronized (st) {
            boolean escalation = st.active && st.lastAlarmLevel != null
                    && fault.level().severity() > st.lastAlarmLevel.severity();
            boolean neverAlarm = st.lastAlarmAtMs == Long.MIN_VALUE;
            boolean suppressWindowPassed = nowMs - st.lastAlarmAtMs >= props.getSuppression().getWindowMs();

            st.active = true;
            st.lastAlarmLevel = fault.level();

            if (!neverAlarm && !escalation && !suppressWindowPassed) {
                log.info("[告警] 抑制重复触发 packId={} fault={} 距上次告警={}ms < 抑制窗口={}ms",
                        packId, fault, nowMs - st.lastAlarmAtMs, props.getSuppression().getWindowMs());
                return List.of();
            }

            AlarmEvent event = new AlarmEvent(
                    packId + ":" + fault.name() + ":" + UUID.randomUUID(),
                    packId, fault, fault.level(), nowMs, evidence);
            st.lastAlarmAtMs = nowMs;
            appendCapped(alarmHistory.computeIfAbsent(packId, k -> new ArrayList<>()), event);
            log.warn("[告警] 外部告警已产生 packId={} fault={} level={} 依据={}",
                    packId, fault, fault.level(), evidence);
            return List.of(event);
        }
    }

    /** 故障成立即执行分级处置（重复触发也会执行但每次都留痕；告警只在窗口外产生）。 */
    public List<ActionResult> executeActions(String packId, FaultKey fault, long nowMs, String evidence) {
        List<AlarmActionType> types = actionsFor(fault.level());
        List<ActionResult> results = new ArrayList<>(types.size());
        Map<String, String> context = Map.of(
                "fault", fault.name(),
                "level", fault.level().name(),
                "suppressionWindowMs", String.valueOf(props.getSuppression().getWindowMs()));
        for (AlarmActionType type : types) {
            ActionResult result = new ActionResult(
                    "ACT-" + actionSeq.incrementAndGet(),
                    packId, type, nowMs, evidence, context);
            appendCapped(actionHistory.computeIfAbsent(packId, k -> new ArrayList<>()), result);
            log.warn("[处置] 执行动作 packId={} action={} fault={} 依据={}", packId, type, fault, evidence);
            results.add(result);
        }
        return results;
    }

    /** 评估服务确认故障恢复（持续 recoveryHold 无违例）后调用。 */
    public void markRecovered(String packId, FaultKey fault, long nowMs) {
        SuppressionState st = stateFor(packId, fault);
        synchronized (st) {
            boolean wasActive = st.active;
            st.active = false;
            st.lastAlarmAtMs = Long.MIN_VALUE;
            st.lastAlarmLevel = null;
            st.recoveredAtMs = nowMs;
            if (wasActive) {
                log.info("[告警] 故障恢复，告警抑制重置 packId={} fault={} recoveredAtMs={}", packId, fault, nowMs);
            }
        }
    }

    public List<AlarmEvent> alarms(String packId) {
        synchronized (alarmHistory) {
            List<AlarmEvent> list = alarmHistory.get(packId);
            return list == null ? List.of() : List.copyOf(list);
        }
    }

    public List<ActionResult> actions(String packId) {
        synchronized (actionHistory) {
            List<ActionResult> list = actionHistory.get(packId);
            return list == null ? List.of() : List.copyOf(list);
        }
    }

    private SuppressionState stateFor(String packId, FaultKey fault) {
        EnumMap<FaultKey, SuppressionState> map =
                suppressions.computeIfAbsent(packId, k -> new EnumMap<>(FaultKey.class));
        synchronized (map) {
            return map.computeIfAbsent(fault, k -> new SuppressionState());
        }
    }

    private List<AlarmActionType> actionsFor(AlarmLevel level) {
        return switch (level) {
            case INFO -> List.of(AlarmActionType.LOG_ALERT);
            case WARN -> List.of(AlarmActionType.LOG_ALERT, AlarmActionType.DASHBOARD_FLAG);
            case CRITICAL -> List.of(AlarmActionType.LOG_ALERT, AlarmActionType.DASHBOARD_FLAG,
                    AlarmActionType.RELAY_CUTOFF);
        };
    }

    private <T> void appendCapped(List<T> list, T item) {
        synchronized (list) {
            list.add(item);
            while (list.size() > HISTORY_CAPACITY) {
                list.remove(0);
            }
        }
    }

    /** 单个 电池包+故障 的抑制状态。 */
    private static final class SuppressionState {
        volatile boolean active;
        long lastAlarmAtMs = Long.MIN_VALUE;
        AlarmLevel lastAlarmLevel;
        long recoveredAtMs = Long.MIN_VALUE;
    }
}
