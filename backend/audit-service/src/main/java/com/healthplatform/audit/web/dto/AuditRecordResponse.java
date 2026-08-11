package com.healthplatform.audit.web.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditRecordResponse(
        UUID id,
        String eventType,
        String aggregateId,
        String sourceTopic,
        String correlationId,
        String payloadJson,
        Instant recordedAt
) {}
