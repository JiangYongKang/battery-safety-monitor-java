package com.github.highcumontoa.batterysafetymonitorjava.web;

import com.github.highcumontoa.batterysafetymonitorjava.model.ActionResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.AlarmEvent;
import com.github.highcumontoa.batterysafetymonitorjava.model.AssessmentResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.IngestResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.PackSnapshot;
import com.github.highcumontoa.batterysafetymonitorjava.service.AlarmService;
import com.github.highcumontoa.batterysafetymonitorjava.service.AssessmentService;
import com.github.highcumontoa.batterysafetymonitorjava.service.IngestService;
import com.github.highcumontoa.batterysafetymonitorjava.service.PackRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.github.highcumontoa.batterysafetymonitorjava.web.dto.TelemetryRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 采样接入与状态查询入口。
 */
@RestController
@RequestMapping("/api")
public class TelemetryController {

    private final IngestService ingestService;
    private final AssessmentService assessmentService;
    private final AlarmService alarmService;
    private final PackRegistry packRegistry;

    public TelemetryController(IngestService ingestService,
                               AssessmentService assessmentService,
                               AlarmService alarmService,
                               PackRegistry packRegistry) {
        this.ingestService = ingestService;
        this.assessmentService = assessmentService;
        this.alarmService = alarmService;
        this.packRegistry = packRegistry;
    }

    @GetMapping("/packs")
    public List<String> packs() {
        return packRegistry.packIds();
    }

    @PostMapping("/telemetry")
    public IngestResult ingest(@RequestBody TelemetryRequest request) {
        return ingestService.ingest(request);
    }

    @PostMapping("/evaluate")
    public List<AssessmentResult> evaluateAll() {
        return assessmentService.evaluateAll();
    }

    @GetMapping("/packs/{packId}")
    public PackSnapshot snapshot(@PathVariable String packId) {
        if (!packRegistry.packIds().contains(packId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未知电池包: " + packId);
        }
        return assessmentService.snapshot(packId);
    }

    @GetMapping("/packs/{packId}/alarms")
    public List<AlarmEvent> alarms(@PathVariable String packId) {
        return alarmService.alarms(packId);
    }

    @GetMapping("/packs/{packId}/actions")
    public List<ActionResult> actions(@PathVariable String packId) {
        return alarmService.actions(packId);
    }
}
