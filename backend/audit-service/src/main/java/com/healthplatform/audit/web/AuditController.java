package com.healthplatform.audit.web;

import com.healthplatform.audit.domain.AuditRecord;
import com.healthplatform.audit.repository.AuditRecordRepository;
import com.healthplatform.audit.web.dto.AuditRecordResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit")
public class AuditController {

    private final AuditRecordRepository auditRecordRepository;

    public AuditController(AuditRecordRepository auditRecordRepository) {
        this.auditRecordRepository = auditRecordRepository;
    }

    @GetMapping("/by-aggregate/{aggregateId}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditRecordResponse> byAggregate(@PathVariable String aggregateId) {
        return auditRecordRepository.findAllByAggregateId(aggregateId).stream().map(this::toResponse).toList();
    }

    private AuditRecordResponse toResponse(AuditRecord r) {
        return new AuditRecordResponse(r.getId(), r.getEventType(), r.getAggregateId(), r.getSourceTopic(),
                r.getCorrelationId(), r.getPayloadJson(), r.getRecordedAt());
    }
}
