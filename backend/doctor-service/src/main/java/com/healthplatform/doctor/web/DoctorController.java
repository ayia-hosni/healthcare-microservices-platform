package com.healthplatform.doctor.web;

import com.healthplatform.doctor.service.DoctorService;
import com.healthplatform.doctor.web.dto.AvailabilitySlotRequest;
import com.healthplatform.doctor.web.dto.AvailabilitySlotResponse;
import com.healthplatform.doctor.web.dto.DoctorRequest;
import com.healthplatform.doctor.web.dto.DoctorResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/doctors")
@Tag(name = "Doctors")
public class DoctorController {

    private final DoctorService doctorService;

    public DoctorController(DoctorService doctorService) {
        this.doctorService = doctorService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DoctorResponse> create(@Valid @RequestBody DoctorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(doctorService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<DoctorResponse>> findAll() {
        return ResponseEntity.ok(doctorService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DoctorResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(doctorService.getById(id));
    }

    @GetMapping("/search")
    public ResponseEntity<List<DoctorResponse>> findBySpecialty(@RequestParam String specialty) {
        return ResponseEntity.ok(doctorService.findBySpecialty(specialty));
    }

    // A doctor's JWT subject is the same UUID as their Doctor.id by convention, so "own
    // schedule only" is an id check against the principal. ADMIN bypasses it. NURSE isn't
    // allowed here: editing availability was never a nurse action.
    @PostMapping("/{id}/availability")
    @PreAuthorize("hasRole('ADMIN') or #id.toString() == authentication.name")
    public ResponseEntity<AvailabilitySlotResponse> addAvailabilitySlot(
            @PathVariable UUID id, @Valid @RequestBody AvailabilitySlotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(doctorService.addAvailabilitySlot(id, request));
    }

    @GetMapping("/{id}/availability")
    public ResponseEntity<List<AvailabilitySlotResponse>> getAvailability(@PathVariable UUID id) {
        return ResponseEntity.ok(doctorService.getAvailability(id));
    }

    @DeleteMapping("/{id}/availability/{slotId}")
    @PreAuthorize("hasRole('ADMIN') or #id.toString() == authentication.name")
    public ResponseEntity<Void> removeAvailabilitySlot(@PathVariable UUID id, @PathVariable UUID slotId) {
        doctorService.removeAvailabilitySlot(id, slotId);
        return ResponseEntity.noContent().build();
    }
}
