package com.healthplatform.billing.web;

import com.healthplatform.billing.service.InvoiceService;
import com.healthplatform.billing.web.dto.InvoiceResponse;
import com.healthplatform.billing.web.dto.PaymentRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "Billing")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','BILLING_CLERK','PATIENT')")
    public ResponseEntity<InvoiceResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(invoiceService.getById(id));
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize("hasAnyRole('ADMIN','BILLING_CLERK','PATIENT')")
    public ResponseEntity<InvoiceResponse> pay(@PathVariable UUID id, @Valid @RequestBody PaymentRequest request) {
        return ResponseEntity.ok(invoiceService.pay(id, request));
    }
}
