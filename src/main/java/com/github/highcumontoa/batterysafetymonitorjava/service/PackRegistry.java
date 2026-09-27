package com.github.highcumontoa.batterysafetymonitorjava.service;

import com.github.highcumontoa.batterysafetymonitorjava.model.FaultKey;
import com.github.highcumontoa.batterysafetymonitorjava.model.HealthState;
import com.github.highcumontoa.batterysafetymonitorjava.model.StateTransition;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 电池包运行态注册表（纯内存实现）。
 *
 * 设计要点：
 * - 每个电池包一把独立锁，接入/评估/告警联动在同一把锁内完成，保证并发上报结论一致；
 * - 已处理批次做幂等记录（有界，超出容量淘汰最旧）；
 * - 采样窗口、故障运行态、状态时间线均保存在 {@link PackState} 中。
 */
@Component
public class PackRegistry {

    private static final int BATCH_ID_CAPACITY = 2048;
    private static final int TIMELINE_CAPACITY = 200;

    private final Map<String, PackState> packs = new LinkedHashMap<>();

    /** 获取或创建电池包运行态（创建本身需串行化）。 */
    public PackState stateFor(String packId) {
        synchronized (packs) {
            return packs.computeIfAbsent(packId, PackState::new);
        }
    }

    /** 返回某包的专用锁；不存在时也会先创建，避免调用方判空。 */
    public Object lockFor(String packId) {
        return stateFor(packId).lock;
    }

    public List<String> packIds() {
        synchronized (packs) {
            return new ArrayList<>(packs.keySet());
        }
    }

    /**
     * 单个电池包的全部运行态。所有可变字段只允许在持有 {@link #lock} 时访问。
     */
    public static final class PackState {
        public final String packId;
        public final Object lock = new Object();

        /** 已接收采样窗口（按 timestampMs 升序追加；乱序旧采样在接入阶段拒绝）。 */
        public final Deque<TelemetrySample> window = new ArrayDeque<>();

        /** 已处理批次 batchId -> sentAtMs，有界淘汰，用于重复上报幂等。 */
        public final Map<String, Long> processedBatches = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > BATCH_ID_CAPACITY;
            }
        };

        public final EnumMap<FaultKey, FaultRuntime> faults = new EnumMap<>(FaultKey.class);

        public final List<StateTransition> timeline = new ArrayList<>();

        public long lastBatchSentAtMs = Long.MIN_VALUE;
        public long lastAcceptedAtMs = Long.MIN_VALUE;
        public long lastAcceptedSampleTs = Long.MIN_VALUE;
        public long lastEvaluatedSampleTs = Long.MIN_VALUE;

        public HealthState currentState = HealthState.NORMAL;
        public int healthScore = 100;

        public PackState(String packId) {
            this.packId = packId;
            for (FaultKey key : FaultKey.values()) {
                faults.put(key, new FaultRuntime());
            }
        }

        public void appendTransition(StateTransition transition) {
            timeline.add(transition);
            while (timeline.size() > TIMELINE_CAPACITY) {
                timeline.remove(0);
            }
        }
    }

    /**
     * 单个故障的运行态。
     * active=false 且 violating=true 表示"持续时间尚未满足的候选故障"，
     * 恢复时需要保持 recoveryHoldMs 无违例才允许真正清除（防止短暂波动导致状态抖动）。
     */
    public static final class FaultRuntime {
        public boolean active;
        public boolean violating;
        public long firstViolationMs = Long.MIN_VALUE;
        public long lastViolationMs = Long.MIN_VALUE;
        public long recoverSinceMs = Long.MIN_VALUE;
        public long clearedAtMs = Long.MIN_VALUE;
        public String evidence = "";

        public void markViolation(long ts, String evidence) {
            if (!violating && !active) {
                firstViolationMs = ts;
            }
            this.violating = true;
            this.lastViolationMs = ts;
            this.recoverSinceMs = Long.MIN_VALUE;
            this.evidence = evidence;
        }

        public void markNonViolation(long ts) {
            this.violating = false;
            if (recoverSinceMs == Long.MIN_VALUE) {
                recoverSinceMs = ts;
            }
        }
    }
}
