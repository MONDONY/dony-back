package com.dony.api.payments.chargeback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/chargebacks")
@PreAuthorize("hasRole('ADMIN')")
public class ChargebackController {

    private final ChargebackRepository repo;

    public ChargebackController(ChargebackRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<Page<ChargebackEntity>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(repo.findAllByOrderByOpenedAtDesc(PageRequest.of(page, size)));
    }
}
