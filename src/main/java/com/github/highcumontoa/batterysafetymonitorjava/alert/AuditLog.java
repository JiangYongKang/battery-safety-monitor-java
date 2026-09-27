package com.github.highcumontoa.batterysafetymonitorjava.alert;

import com.github.highcumontoa.batterysafetymonitorjava.model.AuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 线程安全的内存审计日志：记录状态迁移、告警触发/抑制与处置结果。 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final CopyOnWriteArrayList<AuditRecord> records = new CopyOnWriteArrayList<>();

    public void append(AuditRecord record) {
        records.add(record);
        log.info("AUDIT pack={} type={} detail={}", record.packId(), record.eventType(), record.detail());
    }

    public List<AuditRecord> recordsFor(String packId) {
        return records.stream().filter(r -> r.packId().equals(packId)).toList();
    }

    public List<AuditRecord> all() {
        return List.copyOf(records);
    }
}
