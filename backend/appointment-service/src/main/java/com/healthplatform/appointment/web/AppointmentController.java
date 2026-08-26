package com.healthplatform.appointment.web;

import com.healthplatform.appointment.service.AppointmentService;
import com.healthplatform.appointment.web.dto.*;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments")
@Tag(name = "Appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','PATIENT','NURSE')")
    @RateLimiter(name = "bookingApi", fallbackMethod = "bookRejected")
    public ResponseEntity<AppointmentResponse> book(@Valid @RequestBody BookAppointmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(appointmentService.book(request));
    }

    /**
     * Fires when the bookingApi rate limiter (see application.yml: resilience4j.ratelimiter)
     * rejects a request instead of letting it queue — booking spam/thundering-herd protection,
     * not a correctness check, so callers get a plain 429 to retry shortly.
     */
    private ResponseEntity<AppointmentResponse> bookRejected(BookAppointmentRequest request, RequestNotPermitted ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppointmentResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(appointmentService.getById(id));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PATIENT','DOCTOR','NURSE')")
    public ResponseEntity<java.util.List<AppointmentResponse>> getByPatientId(@RequestParam UUID patientId) {
        return ResponseEntity.ok(appointmentService.getByPatientId(patientId));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','PATIENT','DOCTOR','NURSE')")
    public ResponseEntity<AppointmentResponse> cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request,
                                                       Authentication authentication) {
        String cancelledBy = authentication != null ? authentication.getName() : "system";
        return ResponseEntity.ok(appointmentService.cancel(id, request.reason(), cancelledBy));
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAnyRole('ADMIN','PATIENT','NURSE')")
    public ResponseEntity<AppointmentResponse> reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest request) {
        return ResponseEntity.ok(appointmentService.reschedule(id, request.newStart(), request.newEnd()));
    }
}
