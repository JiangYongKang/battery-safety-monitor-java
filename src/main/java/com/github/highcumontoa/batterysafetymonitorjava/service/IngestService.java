package com.github.highcumontoa.batterysafetymonitorjava.service;

import com.github.highcumontoa.batterysafetymonitorjava.config.MonitorProperties;
import com.github.highcumontoa.batterysafetymonitorjava.model.IngestResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.RejectReason;
import com.github.highcumontoa.batterysafetymonitorjava.model.SampleRejection;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetryBatch;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;
import com.github.highcumontoa.batterysafetymonitorjava.web.dto.TelemetryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 采样接入服务。
 *
 * 拒绝原则（绝不静默丢弃，原因可区分）：
 * - 批次级：协议不支持 / 必填缺失 / 空批次 / 重复批次(DUPLICATE_BATCH) / 乱序迟到批次(STALE_BATCH)；
 * - 采样级：必填缺失 / 超量程（含 NaN、无穷大）/ 序号重复 / 时间戳陈旧 / 时间戳重复。
 *
 * 顺序与幂等策略（结论确定、可解释，不依赖到达时序的偶然调度）：
 * - 批次按 sentAtMs 单调提交：晚于已提交游标的批次才生效；更早到达的批次判 STALE_BATCH，
 *   因此乱序到达不会造成重复累计，也不会让旧数据回退状态；
 * - 同一 batchId 只生效一次，重复上报一律 DUPLICATE_BATCH；
 * - 采样提交时以包内最新采样时间戳为游标，旧采样判 STALE_SAMPLE 拒绝。
 *
 * 接入成功后在同一把包级锁内立即联动评估，保证"接入-评估-处置"链路一致；
 * 不同电池包使用不同锁，互不阻塞。
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final MonitorProperties props;
    private final PackRegistry registry;
    private final AssessmentService assessmentService;

    public IngestService(MonitorProperties props,
                         PackRegistry registry,
                         AssessmentService assessmentService) {
        this.props = props;
        this.registry = registry;
        this.assessmentService = assessmentService;
    }

    public IngestResult ingest(TelemetryRequest request) {
        if (request == null) {
            return batchReject(null, null, RejectReason.MALFORMED_PAYLOAD, "请求体为空");
        }
        String packId = request.packId;
        String batchId = request.batchId;

        if (isBlank(packId)) {
            return batchReject(batchId, null, RejectReason.MISSING_REQUIRED_FIELD, "缺少必填字段 packId");
        }
        if (isBlank(batchId)) {
            return batchReject(null, packId, RejectReason.MISSING_REQUIRED_FIELD, "缺少必填字段 batchId");
        }
        if (isBlank(request.protocolVersion)) {
            return batchReject(batchId, packId, RejectReason.MISSING_REQUIRED_FIELD, "缺少必填字段 protocolVersion");
        }
        if (request.sentAtMs == null) {
            return batchReject(batchId, packId, RejectReason.MISSING_REQUIRED_FIELD, "缺少必填字段 sentAtMs");
        }
        if (!props.getSupportedProtocols().contains(request.protocolVersion)) {
            return batchReject(batchId, packId, RejectReason.PROTOCOL_VERSION_UNSUPPORTED,
                    "不支持的协议版本: " + request.protocolVersion + "，支持: " + props.getSupportedProtocols());
        }
        if (request.samples == null || request.samples.isEmpty()) {
            return batchReject(batchId, packId, RejectReason.EMPTY_BATCH, "批次不包含任何采样");
        }

        log.info("[接入] packId={} batchId={} protocol={} sentAtMs={} 上报采样数={}",
                packId, batchId, request.protocolVersion, request.sentAtMs, request.samples.size());

        PackRegistry.PackState state = registry.stateFor(packId);
        synchronized (state.lock) {
            // 重复批次：同一 batchId 只生效一次，不产生任何重复累计
            if (state.processedBatches.containsKey(batchId)) {
                long firstAt = state.processedBatches.get(batchId);
                log.warn("[接入] 重复批次被拒绝 packId={} batchId={} 首次sentAtMs={}", packId, batchId, firstAt);
                return batchReject(batchId, packId, RejectReason.DUPLICATE_BATCH,
                        "批次 " + batchId + " 已处理过(首次sentAtMs=" + firstAt + ")，重复上报不累计");
            }
            // 乱序迟到批次：sentAtMs 早于已提交游标 -> 拒绝，旧数据不得回退较新结论
            if (state.lastBatchSentAtMs != Long.MIN_VALUE
                    && request.sentAtMs < state.lastBatchSentAtMs) {
                log.warn("[接入] 乱序迟到批次被拒绝 packId={} batchId={} sentAtMs={} < last={}",
                        packId, batchId, request.sentAtMs, state.lastBatchSentAtMs);
                return batchReject(batchId, packId, RejectReason.STALE_BATCH,
                        "批次乱序迟到: sentAtMs=" + request.sentAtMs + " 早于已提交游标 "
                                + state.lastBatchSentAtMs + "，旧数据不得覆盖新结论");
            }

            List<SampleRejection> rejections = new ArrayList<>();
            List<TelemetrySample> accepted = new ArrayList<>();
            Set<Integer> seenSeqs = new HashSet<>();
            Set<Long> seenTimestamps = new HashSet<>();

            for (TelemetryRequest.SampleDto dto : request.samples) {
                int seq = dto.seq == null ? -1 : dto.seq;
                String missing = checkMissing(dto, request.protocolVersion);
                if (missing != null) {
                    rejections.add(new SampleRejection(seq, RejectReason.MISSING_REQUIRED_FIELD, missing));
                    continue;
                }
                String rangeMsg = checkRange(dto);
                if (rangeMsg != null) {
                    rejections.add(new SampleRejection(seq, RejectReason.OUT_OF_RANGE, rangeMsg));
                    continue;
                }
                if (!seenSeqs.add(dto.seq)) {
                    rejections.add(new SampleRejection(seq, RejectReason.DUPLICATE_SAMPLE,
                            "批次内 seq=" + dto.seq + " 重复"));
                    continue;
                }
                if (!seenTimestamps.add(dto.timestampMs)) {
                    rejections.add(new SampleRejection(seq, RejectReason.DUPLICATE_SAMPLE,
                            "批次内 timestampMs=" + dto.timestampMs + " 与其他采样重复"));
                    continue;
                }
                if (dto.timestampMs <= state.lastAcceptedSampleTs) {
                    rejections.add(new SampleRejection(seq, RejectReason.STALE_SAMPLE,
                            "采样时间戳 " + dto.timestampMs + " 不大于包内最新已接收时间戳 "
                                    + state.lastAcceptedSampleTs + "，旧数据不得覆盖新结论"));
                    continue;
                }
                accepted.add(new TelemetrySample(dto.seq, dto.timestampMs, dto.voltageV, dto.currentA,
                        dto.tempC, dto.insulationKOhm));
            }

            for (SampleRejection r : rejections) {
                log.warn("[接入] 采样拒绝 packId={} batchId={} seq={} reason={} detail={}",
                        packId, batchId, r.seq(), r.reason(), r.detail());
            }

            if (accepted.isEmpty()) {
                // 全部采样非法：批次不生效（不登记 batchId、不推进游标），错误通过拒绝清单区分
                return new IngestResult(batchId, packId, false, 0, rejections.size(),
                        List.copyOf(rejections), null, "批次内无有效采样，整批未生效");
            }

            accepted.sort(Comparator.comparingLong(TelemetrySample::timestampMs));

            // 提交：登记批次、推进游标、写入窗口（全部在锁内）
            state.processedBatches.put(batchId, request.sentAtMs);
            state.lastBatchSentAtMs = request.sentAtMs;
            state.lastAcceptedAtMs = request.sentAtMs;
            long maxTs = accepted.get(accepted.size() - 1).timestampMs();
            state.lastAcceptedSampleTs = maxTs;
            state.window.addAll(accepted);

            TelemetryBatch batch = new TelemetryBatch(batchId, packId, request.protocolVersion,
                    request.sentAtMs, List.copyOf(accepted));

            log.info("[接入] 已接收 packId={} batchId={} 有效采样={} 拒绝采样={} 最新采样ts={}",
                    packId, batchId, accepted.size(), rejections.size(), maxTs);

            // 同锁联动评估，保证并发上报下结论一致
            assessmentService.evaluateUnderLock(state, batch);

            return new IngestResult(batchId, packId, true, accepted.size(), rejections.size(),
                    List.copyOf(rejections), null,
                    "接收 " + accepted.size() + " 条，拒绝 " + rejections.size() + " 条，评估已联动执行");
        }
    }

    private String checkMissing(TelemetryRequest.SampleDto dto, String protocolVersion) {
        if (dto.seq == null) {
            return "缺少必填字段 seq";
        }
        if (dto.timestampMs == null) {
            return "缺少必填字段 timestampMs";
        }
        if (dto.voltageV == null) {
            return "缺少必填字段 voltageV";
        }
        if (dto.currentA == null) {
            return "缺少必填字段 currentA";
        }
        if (dto.tempC == null) {
            return "缺少必填字段 tempC";
        }
        if ("v2".equals(protocolVersion) && dto.insulationKOhm == null) {
            return "协议 v2 要求必填 insulationKOhm";
        }
        return null;
    }

    private String checkRange(TelemetryRequest.SampleDto dto) {
        MonitorProperties.Range r = props.getRange();
        if (!Double.isFinite(dto.voltageV) || dto.voltageV < r.getMinVoltage() || dto.voltageV > r.getMaxVoltage()) {
            return "voltageV=" + dto.voltageV + " 超出量程 [" + r.getMinVoltage() + ", " + r.getMaxVoltage() + "]V";
        }
        if (!Double.isFinite(dto.currentA) || dto.currentA < r.getMinCurrent() || dto.currentA > r.getMaxCurrent()) {
            return "currentA=" + dto.currentA + " 超出量程 [" + r.getMinCurrent() + ", " + r.getMaxCurrent() + "]A";
        }
        if (!Double.isFinite(dto.tempC) || dto.tempC < r.getMinTemp() || dto.tempC > r.getMaxTemp()) {
            return "tempC=" + dto.tempC + " 超出量程 [" + r.getMinTemp() + ", " + r.getMaxTemp() + "]℃";
        }
        if (dto.insulationKOhm != null
                && (!Double.isFinite(dto.insulationKOhm)
                || dto.insulationKOhm < r.getMinInsulation()
                || dto.insulationKOhm > r.getMaxInsulation())) {
            return "insulationKOhm=" + dto.insulationKOhm + " 超出量程 ["
                    + r.getMinInsulation() + ", " + r.getMaxInsulation() + "]kΩ";
        }
        return null;
    }

    private IngestResult batchReject(String batchId, String packId, RejectReason reason, String message) {
        log.warn("[接入] 批次拒绝 batchId={} packId={} reason={} detail={}", batchId, packId, reason, message);
        return new IngestResult(batchId, packId, false, 0, 0, List.of(), reason, message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
