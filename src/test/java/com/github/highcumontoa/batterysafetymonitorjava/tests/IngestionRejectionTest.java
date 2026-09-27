package com.github.highcumontoa.batterysafetymonitorjava.tests;

import com.github.highcumontoa.batterysafetymonitorjava.model.IngestResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.RejectReason;
import com.github.highcumontoa.batterysafetymonitorjava.service.IngestService;
import com.github.highcumontoa.batterysafetymonitorjava.tests.support.TelemetryFactory;
import com.github.highcumontoa.batterysafetymonitorjava.web.dto.TelemetryRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;

import static com.github.highcumontoa.batterysafetymonitorjava.tests.support.TelemetryFactory.T0;
import static com.github.highcumontoa.batterysafetymonitorjava.tests.support.TelemetryFactory.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接入层：字段缺失、超量程、协议必填、重复上报、乱序到达的拒绝原因必须可区分。
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class IngestionRejectionTest {

    private static final Logger log = LoggerFactory.getLogger(IngestionRejectionTest.class);

    @Autowired
    private IngestService ingestService;

    @Test
    void missingBatchFieldsAreRejectedWithDistinctReasons() {
        TelemetryRequest noPack = TelemetryFactory.batch("B1", null, "v2", T0,
                List.of(sample(0, T0, 3.7, 10.0, 25.0, 800.0)));
        IngestResult r1 = ingestService.ingest(noPack);
        log.info("[用例] 缺packId -> accepted={} reason={} message={}", r1.accepted(), r1.batchRejectReason(), r1.message());
        assertFalse(r1.accepted());
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD, r1.batchRejectReason());

        TelemetryRequest noSent = TelemetryFactory.batch("B2", "P-MISS", "v2", 0,
                List.of(sample(0, T0, 3.7, 10.0, 25.0, 800.0)));
        noSent.sentAtMs = null;
        IngestResult r2 = ingestService.ingest(noSent);
        log.info("[用例] 缺sentAtMs -> accepted={} reason={}", r2.accepted(), r2.batchRejectReason());
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD, r2.batchRejectReason());

        TelemetryRequest badProto = TelemetryFactory.batch("B3", "P-MISS", "v9", T0,
                List.of(sample(0, T0, 3.7, 10.0, 25.0, 800.0)));
        IngestResult r3 = ingestService.ingest(badProto);
        log.info("[用例] 协议v9 -> accepted={} reason={} message={}", r3.accepted(), r3.batchRejectReason(), r3.message());
        assertEquals(RejectReason.PROTOCOL_VERSION_UNSUPPORTED, r3.batchRejectReason());

        TelemetryRequest empty = TelemetryFactory.batch("B4", "P-MISS", "v2", T0, List.of());
        IngestResult r4 = ingestService.ingest(empty);
        log.info("[用例] 空批次 -> accepted={} reason={}", r4.accepted(), r4.batchRejectReason());
        assertEquals(RejectReason.EMPTY_BATCH, r4.batchRejectReason());
    }

    @Test
    void v2RequiresInsulationAndRangeIsEnforcedPerField() {
        // v2 缺绝缘 -> MISSING_REQUIRED_FIELD
        TelemetryRequest v2NoIns = TelemetryFactory.batch("B10", "P-FIELDS", "v2", T0,
                List.of(sample(0, T0, 3.7, 10.0, 25.0, null)));
        IngestResult r1 = ingestService.ingest(v2NoIns);
        log.info("[用例] v2缺绝缘 -> accepted={} 采样拒绝={} reason={}",
                r1.accepted(), r1.sampleRejections(), r1.sampleRejections().get(0).reason());
        assertFalse(r1.accepted());
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD, r1.sampleRejections().get(0).reason());

        // v1 无绝缘字段 -> 正常接收
        TelemetryRequest v1 = TelemetryFactory.batch("B11", "P-FIELDS", "v1", T0 + 1,
                List.of(sample(0, T0 + 1, 3.7, 10.0, 25.0, null)));
        IngestResult r2 = ingestService.ingest(v1);
        log.info("[用例] v1无绝缘 -> accepted={} 有效={} 拒绝={}", r2.accepted(), r2.acceptedSamples(), r2.rejectedSamples());
        assertTrue(r2.accepted());

        // 同一批内：缺温度 / 电压超量程 / 电流越界，原因逐条可区分，其余采样仍接收
        TelemetryRequest mixed = TelemetryFactory.batch("B12", "P-FIELDS", "v2", T0 + 2, List.of(
                sample(0, T0 + 2, 3.7, 10.0, null, 800.0),
                sample(1, T0 + 3, 6.0, 10.0, 25.0, 800.0),
                sample(2, T0 + 4, 3.7, 600.0, 25.0, 800.0),
                sample(3, T0 + 5, 3.7, 10.0, 25.0, 800.0)));
        IngestResult r3 = ingestService.ingest(mixed);
        log.info("[用例] 混合非法批次 -> accepted={} 有效={} 拒绝清单={}",
                r3.accepted(), r3.acceptedSamples(), r3.sampleRejections());
        assertTrue(r3.accepted());
        assertEquals(1, r3.acceptedSamples());
        assertEquals(3, r3.rejectedSamples());
        assertEquals(RejectReason.MISSING_REQUIRED_FIELD, r3.sampleRejections().get(0).reason());
        assertEquals(RejectReason.OUT_OF_RANGE, r3.sampleRejections().get(1).reason());
        assertEquals(RejectReason.OUT_OF_RANGE, r3.sampleRejections().get(2).reason());
        assertNotNull(r3.sampleRejections().get(1).detail());
    }

    @Test
    void duplicateBatchIsIdempotent() {
        var samples = TelemetryFactory.constant(0, T0, 2_000, 3, 3.7, 10.0, 25.0, 800.0);
        TelemetryRequest first = TelemetryFactory.batch("DUP-1", "P-DUP", "v2", T0 + 4_000, samples);
        IngestResult ok = ingestService.ingest(first);
        assertTrue(ok.accepted());
        log.info("[用例] 首次上报 -> accepted={} 有效={}", ok.accepted(), ok.acceptedSamples());

        // 完全相同的批次再次上报 -> DUPLICATE_BATCH，不重复累计
        IngestResult dup = ingestService.ingest(first);
        log.info("[用例] 重复批次 -> accepted={} reason={} message={}", dup.accepted(), dup.batchRejectReason(), dup.message());
        assertFalse(dup.accepted());
        assertEquals(RejectReason.DUPLICATE_BATCH, dup.batchRejectReason());
    }

    @Test
    void outOfOrderBatchArrivingLaterIsRejectedStale() {
        // 先接收 sentAt=T0+24s 的批次，游标推进
        ingestService.ingest(TelemetryFactory.batch("OO-NEW", "P-OO", "v2", T0 + 24_000,
                TelemetryFactory.constant(20, T0 + 20_000, 2_000, 3, 3.7, 10.0, 25.0, 800.0)));
        // 随后到达 sentAt=T0+14s 的更旧批次 -> STALE_BATCH，不覆盖新结论
        IngestResult stale = ingestService.ingest(TelemetryFactory.batch("OO-OLD", "P-OO", "v2", T0 + 14_000,
                TelemetryFactory.constant(0, T0 + 10_000, 2_000, 3, 3.7, 10.0, 25.0, 800.0)));
        log.info("[用例] 后到的旧批次 -> accepted={} reason={} message={}",
                stale.accepted(), stale.batchRejectReason(), stale.message());
        assertFalse(stale.accepted());
        assertEquals(RejectReason.STALE_BATCH, stale.batchRejectReason());
    }



    @Test
    void duplicateSeqAndStaleSampleAndDuplicateTimestampAreRejected() {
        // 批次内 seq 重复、timestamp 重复
        TelemetryRequest req = TelemetryFactory.batch("S1", "P-SDUP", "v2", T0 + 5_000, List.of(
                sample(0, T0, 3.7, 10.0, 25.0, 800.0),
                sample(0, T0 + 1_000, 3.7, 10.0, 25.0, 800.0),
                sample(2, T0, 3.7, 10.0, 26.0, 800.0)));
        IngestResult r = ingestService.ingest(req);
        log.info("[用例] seq重复+时间戳重复 -> 有效={} 拒绝={} 清单={}",
                r.acceptedSamples(), r.rejectedSamples(), r.sampleRejections());
        // seq=0 两条 -> 第二条因 seq 重复被拒；seq=2 与首条同时间戳 -> 时间戳重复被拒
        assertEquals(1, r.acceptedSamples());
        assertEquals(2, r.rejectedSamples());
        assertEquals(RejectReason.DUPLICATE_SAMPLE, r.sampleRejections().get(0).reason());
        assertEquals(RejectReason.DUPLICATE_SAMPLE, r.sampleRejections().get(1).reason());

        // 下一批次中夹带一条旧时间戳采样 -> STALE_SAMPLE
        TelemetryRequest req2 = TelemetryFactory.batch("S2", "P-SDUP", "v2", T0 + 10_000, List.of(
                sample(10, T0 - 1_000, 3.7, 10.0, 25.0, 800.0),
                sample(11, T0 + 6_000, 3.7, 10.0, 25.0, 800.0)));
        IngestResult r2 = ingestService.ingest(req2);
        log.info("[用例] 夹带旧采样 -> 有效={} 拒绝={} 清单={}",
                r2.acceptedSamples(), r2.rejectedSamples(), r2.sampleRejections());
        assertEquals(1, r2.acceptedSamples());
        assertEquals(RejectReason.STALE_SAMPLE, r2.sampleRejections().get(0).reason());
    }
}
