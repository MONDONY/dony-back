package com.dony.api.payments.chargeback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/chargebacks")
@PreAuthorize("hasRole('ADMIN')")
public class ChargebackController {

    private final ChargebackService service;

    public ChargebackController(ChargebackService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Page<ChargebackDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listAll(PageRequest.of(page, size)));
    }
}
