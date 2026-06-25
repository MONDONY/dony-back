package com.dony.api.admin;

import com.dony.api.admin.dto.AdminChargebackResponse;
import com.dony.api.payments.chargeback.ChargebackRepository;
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
public class AdminChargebackController {

    private final ChargebackRepository chargebackRepository;

    public AdminChargebackController(ChargebackRepository chargebackRepository) {
        this.chargebackRepository = chargebackRepository;
    }

    @GetMapping
    public ResponseEntity<Page<AdminChargebackResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                chargebackRepository.findAllByOrderByOpenedAtDesc(PageRequest.of(page, size))
                        .map(AdminChargebackResponse::from));
    }
}
