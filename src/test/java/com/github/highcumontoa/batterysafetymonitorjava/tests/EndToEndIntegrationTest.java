package com.github.highcumontoa.batterysafetymonitorjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：REST 上报 -> 评估 -> 告警 -> 审计，多入口结论一致。
 * 模拟器在测试中关闭，数据全部由测试注入。
 */
@SpringBootTest(properties = {"simulator.enabled=false", "server.port=0"})
@AutoConfigureMockMvc
class EndToEndIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(EndToEndIntegrationTest.class);
    private static final long BASE_TS = 5_000_000L;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper om = new ObjectMapper();

    private String batchJson(String packId, String protocol, List<String> samples) {
        return """
                {"batchId":"b-%d","packId":"%s","protocolVersion":"%s","samples":[%s]}
                """.formatted(System.nanoTime(), packId, protocol, String.join(",", samples));
    }

    private String sampleJson(Long seq, long ts, Double volt, Double temp, Boolean insulation) {
        StringBuilder sb = new StringBuilder("{");
        if (seq != null) sb.append("\"sequenceNumber\":").append(seq).append(",");
        sb.append("\"timestampMillis\":").append(ts).append(",");
        if (volt != null) sb.append("\"voltage\":").append(volt).append(",");
        sb.append("\"current\":50.0,");
        if (temp != null) sb.append("\"temperature\":").append(temp).append(",");
        if (insulation != null) sb.append("\"insulationOk\":").append(insulation).append(",");
        sb.append("\"packId\":null}");
        return sb.toString();
    }

    private JsonNode postBatch(String packId, String protocol, List<String> samples) throws Exception {
        String body = batchJson(packId, protocol, samples);
        log.info("POST /api/telemetry/batch pack={} protocol={} samples={}", packId, protocol, samples);
        MvcResult res = mockMvc.perform(post("/api/telemetry/batch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode result = om.readTree(res.getResponse().getContentAsString());
        log.info("INGEST RESULT accepted={} rejected={} rejections={}",
                result.get("acceptedCount"), result.get("rejectedCount"), result.get("rejections"));
        return result;
    }

    @Test
    void restIngestMixedBatchAndQueryChain() throws Exception {
        String pack = "PACK-E2E";
        // 混合批次：5 条 60°C 有效 + 1 条缺温度 + 1 条超量程
        List<String> samples = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            samples.add(sampleJson((long) i, BASE_TS + i * 1000L, 400.0, 60.0, true));
        }
        samples.add(sampleJson(7L, BASE_TS + 7000L, 400.0, null, true));   // 缺温度
        samples.add(sampleJson(8L, BASE_TS + 8000L, 1500.0, 60.0, true));  // 电压超量程
        JsonNode ingest = postBatch(pack, "V2", samples);
        assertEquals(6, ingest.get("acceptedCount").asInt());
        assertEquals(2, ingest.get("rejectedCount").asInt());

        // 评估结论：6 条 60°C 持续 -> WARNING
        MvcResult assessRes = mockMvc.perform(get("/api/monitor/{pack}/assessment", pack))
                .andExpect(status().isOk()).andReturn();
        JsonNode assessment = om.readTree(assessRes.getResponse().getContentAsString());
        log.info("ASSESSMENT state={} basis={}", assessment.get("state"), assessment.get("basis"));
        assertEquals("WARNING", assessment.get("state").asText());

        // 持续 2s+ 后应产生 WARN 告警与 LIMIT_POWER 处置（样本时间戳推进 2.5s）
        List<String> more = new ArrayList<>();
        for (int i = 9; i <= 11; i++) {
            more.add(sampleJson((long) i, BASE_TS + i * 1000L, 400.0, 60.0, true));
        }
        postBatch(pack, "V2", more);

        MvcResult alertsRes = mockMvc.perform(get("/api/monitor/{pack}/alerts", pack))
                .andExpect(status().isOk()).andReturn();
        JsonNode alerts = om.readTree(alertsRes.getResponse().getContentAsString());
        log.info("ALERTS {}", alerts);
        assertEquals(1, alerts.size());
        assertEquals("WARN", alerts.get(0).get("level").asText());
        assertEquals("LIMIT_POWER", alerts.get(0).get("action").asText());

        // 审计时间线包含状态迁移、告警触发与处置
        MvcResult auditRes = mockMvc.perform(get("/api/monitor/{pack}/audit", pack))
                .andExpect(status().isOk()).andReturn();
        JsonNode audit = om.readTree(auditRes.getResponse().getContentAsString());
        List<String> eventTypes = new ArrayList<>();
        audit.forEach(n -> eventTypes.add(n.get("eventType").asText()));
        log.info("AUDIT eventTypes={}", eventTypes);
        assertTrue(eventTypes.contains("STATE_TRANSITION"));
        assertTrue(eventTypes.contains("ALERT_TRIGGERED"));
        assertTrue(eventTypes.contains("DISPOSAL_EXECUTED"));
    }

    @Test
    void differentEntriesConvergeToSameConclusion() throws Exception {
        // 同一包经由两个独立批次入口上报相同数据流，结论应与单入口一致
        String pack = "PACK-MULTI-ENTRY";
        List<String> part1 = new ArrayList<>();
        List<String> part2 = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            part1.add(sampleJson((long) i, BASE_TS + i * 1000L, 400.0, 62.0, true));
        }
        for (int i = 4; i <= 6; i++) {
            part2.add(sampleJson((long) i, BASE_TS + i * 1000L, 400.0, 62.0, true));
        }
        postBatch(pack, "V2", part1);
        postBatch(pack, "V2", part2);

        MvcResult res = mockMvc.perform(get("/api/monitor/{pack}/assessment", pack))
                .andExpect(status().isOk()).andReturn();
        JsonNode assessment = om.readTree(res.getResponse().getContentAsString());
        log.info("MULTI-ENTRY state={} basis={}", assessment.get("state"), assessment.get("basis"));
        assertEquals("WARNING", assessment.get("state").asText());
    }

    @Test
    void unknownPackAssessmentReturns404() throws Exception {
        mockMvc.perform(get("/api/monitor/{pack}/assessment", "PACK-NOPE"))
                .andExpect(status().isNotFound());
        log.info("JUDGE unknown pack -> 404 as expected");
    }
}
