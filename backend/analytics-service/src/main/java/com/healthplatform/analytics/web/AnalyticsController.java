package com.healthplatform.analytics.web;

import com.healthplatform.analytics.domain.EventCounter;
import com.healthplatform.analytics.repository.EventCounterRepository;
import com.healthplatform.analytics.web.dto.EventCounterResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics")
public class AnalyticsController {

    private final EventCounterRepository eventCounterRepository;

    public AnalyticsController(EventCounterRepository eventCounterRepository) {
        this.eventCounterRepository = eventCounterRepository;
    }

    @GetMapping("/counters")
    @PreAuthorize("hasRole('ADMIN')")
    public List<EventCounterResponse> countersFor(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return eventCounterRepository.findAllByCounterDate(date).stream().map(this::toResponse).toList();
    }

    private EventCounterResponse toResponse(EventCounter c) {
        return new EventCounterResponse(c.getId(), c.getEventType(), c.getCounterDate(), c.getCount());
    }
}
