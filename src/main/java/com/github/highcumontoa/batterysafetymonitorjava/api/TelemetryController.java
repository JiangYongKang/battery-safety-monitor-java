package com.github.highcumontoa.batterysafetymonitorjava.api;

import com.github.highcumontoa.batterysafetymonitorjava.ingest.TelemetryIngestionService;
import com.github.highcumontoa.batterysafetymonitorjava.model.IngestResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.SampleBatch;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 遥测接入入口。 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryIngestionService ingestionService;

    public TelemetryController(TelemetryIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/batch")
    public IngestResult ingestBatch(@RequestBody SampleBatch batch) {
        return ingestionService.ingest(batch);
    }
}
