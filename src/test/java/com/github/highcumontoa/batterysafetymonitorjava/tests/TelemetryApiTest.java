package com.github.highcumontoa.batterysafetymonitorjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口：正常上报、畸形 JSON 归类、重复上报幂等、状态/告警/处置查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TelemetryApiTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryApiTest.class);

    @Autowired
    private MockMvc mockMvc;

    private String validPayload(String batchId, double temp) {
        return """
                {"batchId":"%s","packId":"PACK-WEB","protocolVersion":"v2","sentAtMs":1700000012000,"samples":[
                  {"seq":0,"timestampMs":1700000000000,"voltageV":3.7,"currentA":50.0,"tempC":%f,"insulationKOhm":800.0},
                  {"seq":1,"timestampMs":1700000003000,"voltageV":3.7,"currentA":50.0,"tempC":%f,"insulationKOhm":800.0},
                  {"seq":2,"timestampMs":1700000006000,"voltageV":3.7,"currentA":50.0,"tempC":%f,"insulationKOhm":800.0},
                  {"seq":3,"timestampMs":1700000009000,"voltageV":3.7,"currentA":50.0,"tempC":%f,"insulationKOhm":800.0},
                  {"seq":4,"timestampMs":1700000012000,"voltageV":3.7,"currentA":50.0,"tempC":%f,"insulationKOhm":800.0}
                ]}
                """.formatted(batchId, temp, temp, temp, temp, temp);
    }

    @Test
    void validIngestThenQueryStateAlarmsAndActions() throws Exception {
        String body = validPayload("WEB-1", 50.0);
        log.info("[Web用例] 上报触发温度预警 payload={}", body.replaceAll("\\s+", ""));
        mockMvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.acceptedSamples").value(5));

        mockMvc.perform(get("/api/packs/PACK-WEB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WARNING"))
                .andExpect(jsonPath("$.healthScore").value(92))
                .andDo(r -> log.info("[Web用例] 状态查询结果={}", r.getResponse().getContentAsString()));

        mockMvc.perform(get("/api/packs/PACK-WEB/alarms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fault").value("TEMP_WARN"));

        mockMvc.perform(get("/api/packs/PACK-WEB/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andDo(r -> log.info("[Web用例] 处置审计结果={}", r.getResponse().getContentAsString()));
    }

    @Test
    void duplicatePostIsIdempotentOverHttp() throws Exception {
        String body = validPayload("WEB-DUP", 30.0);
        mockMvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        log.info("[Web用例] 重复HTTP上报同一batchId，期望 DUPLICATE_BATCH");
        mockMvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.batchRejectReason").value("DUPLICATE_BATCH"));
    }

    @Test
    void malformedJsonIsClassifiedAsMalformedPayload() throws Exception {
        String bad = "{not-json";
        log.info("[Web用例] 畸形JSON={}", bad);
        mockMvc.perform(post("/api/telemetry").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("MALFORMED_PAYLOAD"));
    }

    @Test
    void unknownPackReturns404() throws Exception {
        mockMvc.perform(get("/api/packs/NO-SUCH-PACK"))
                .andExpect(status().isNotFound());
    }
}
