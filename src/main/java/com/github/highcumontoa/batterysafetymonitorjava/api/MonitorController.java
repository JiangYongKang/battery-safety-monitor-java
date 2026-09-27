package com.github.highcumontoa.batterysafetymonitorjava.api;

import com.github.highcumontoa.batterysafetymonitorjava.alert.AlertService;
import com.github.highcumontoa.batterysafetymonitorjava.alert.AuditLog;
import com.github.highcumontoa.batterysafetymonitorjava.assess.AssessmentService;
import com.github.highcumontoa.batterysafetymonitorjava.model.AlertEvent;
import com.github.highcumontoa.batterysafetymonitorjava.model.AssessmentResult;
import com.github.highcumontoa.batterysafetymonitorjava.model.AuditRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 监控查询入口：评估结论、告警、审计时间线。 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final AssessmentService assessmentService;
    private final AlertService alertService;
    private final AuditLog auditLog;

    public MonitorController(AssessmentService assessmentService, AlertService alertService, AuditLog auditLog) {
        this.assessmentService = assessmentService;
        this.alertService = alertService;
        this.auditLog = auditLog;
    }

    @GetMapping("/{packId}/assessment")
    public ResponseEntity<AssessmentResult> assessment(@PathVariable String packId) {
        return assessmentService.currentAssessment(packId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/alerts")
    public List<AlertEvent> allAlerts() {
        return alertService.alerts();
    }

    @GetMapping("/{packId}/alerts")
    public List<AlertEvent> alerts(@PathVariable String packId) {
        return alertService.alertsFor(packId);
    }

    @GetMapping("/{packId}/audit")
    public List<AuditRecord> audit(@PathVariable String packId) {
        return auditLog.recordsFor(packId);
    }
}
