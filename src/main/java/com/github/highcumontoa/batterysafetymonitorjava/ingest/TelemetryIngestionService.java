package com.github.highcumontoa.batterysafetymonitorjava.ingest;

import com.github.highcumontoa.batterysafetymonitorjava.assess.AssessmentService;
import com.github.highcumontoa.batterysafetymonitorjava.model.IngestResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.ProtocolVersion;
import com.github.highcumontoa.batterysafetymonitorjava.model.RejectionReason;
import com.github.highcumontoa.batterysafetymonitorjava.model.SampleBatch;
import com.github.highcumontoa.batterysafetymonitorjava.model.SampleVerdict;
import com.github.highcumontoa.batterysafetymonitorjava.model.TelemetrySample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 采样接入链路：校验 → 去重 → 转发评估。
 * <ul>
 *   <li>字段缺失 / 超量程 / 协议必填项不满足：逐条给出可区分拒绝原因，不静默丢弃；</li>
 *   <li>同一包内序号重复：拒绝为 DUPLICATE_SAMPLE，不重复累计；</li>
 *   <li>乱序到达（序号未见过但时间戳不新）：受理但由滑动窗口拒绝入窗，状态不回退；</li>
 *   <li>按 packId 串行化，并发上报结果一致。</li>
 * </ul>
 */
@Service
public class TelemetryIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestionService.class);

    private final SampleValidator validator;
    private final AssessmentService assessmentService;
    private final ConcurrentHashMap<String, PackIngestState> packStates = new ConcurrentHashMap<>();

    public TelemetryIngestionService(SampleValidator validator, AssessmentService assessmentService) {
        this.validator = validator;
        this.assessmentService = assessmentService;
    }

    public IngestResult ingest(SampleBatch batch) {
        if (batch == null) {
            return new IngestResult(null, null, 0, 0, List.of());
        }
        ProtocolVersion version = batch.getProtocolVersion() != null
                ? batch.getProtocolVersion() : ProtocolVersion.V1;
        List<TelemetrySample> samples = batch.getSamples() != null ? batch.getSamples() : List.of();

        List<IngestResult.RejectedSample> rejections = new ArrayList<>();
        int accepted = 0;

        for (TelemetrySample sample : samples) {
            // 批次级 packId 兜底：样本缺 packId 时以批次为准
            if (sample != null && (sample.getPackId() == null || sample.getPackId().isBlank())) {
                sample.setPackId(batch.getPackId());
            }

            SampleVerdict verdict = validator.validate(sample, version);
            if (!verdict.accepted()) {
                rejections.add(new IngestResult.RejectedSample(
                        sample != null ? sample.getSequenceNumber() : null, verdict.reason(), verdict.detail()));
                log.info("INGEST reject batch={} pack={} seq={} reason={} detail={}",
                        batch.getBatchId(), batch.getPackId(),
                        sample != null ? sample.getSequenceNumber() : null, verdict.reason(), verdict.detail());
                continue;
            }

            // 去重：按 packId 串行
            PackIngestState state = packStates.computeIfAbsent(sample.getPackId(), id -> new PackIngestState());
            synchronized (state) {
                if (!state.seenSequences.add(sample.getSequenceNumber())) {
                    rejections.add(new IngestResult.RejectedSample(sample.getSequenceNumber(),
                            RejectionReason.DUPLICATE_SAMPLE,
                            "sequenceNumber " + sample.getSequenceNumber() + " already ingested for pack "
                                    + sample.getPackId()));
                    log.info("INGEST reject batch={} pack={} seq={} reason=DUPLICATE_SAMPLE",
                            batch.getBatchId(), sample.getPackId(), sample.getSequenceNumber());
                    continue;
                }
            }
            accepted++;
            assessmentService.onSample(sample);
        }

        IngestResult result = new IngestResult(batch.getBatchId(), batch.getPackId(),
                accepted, rejections.size(), List.copyOf(rejections));
        log.info("INGEST batch={} pack={} protocol={} accepted={} rejected={}",
                batch.getBatchId(), batch.getPackId(), version, accepted, rejections.size());
        return result;
    }

    /** 单包接入状态：已见序号集合（去重依据）。 */
    private static final class PackIngestState {
        final Set<Long> seenSequences = new HashSet<>();
    }
}
