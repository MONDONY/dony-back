package com.dony.api.admin;

import com.dony.api.admin.dto.AdminAlertResponse;
import com.dony.api.admin.dto.ResolveAlertRequest;
import com.dony.api.common.DonyBusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@RestController
@RequestMapping("/admin/alerts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAlertController {

    private final AdminAlertRepository alertRepo;

    public AdminAlertController(AdminAlertRepository alertRepo) {
        this.alertRepo = alertRepo;
    }

    @GetMapping
    public ResponseEntity<Page<AdminAlertResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AdminAlertResponse> result = alertRepo
                .findFiltered(type, severity, resolved,
                        PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(AdminAlertResponse::from);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/resolve")
    @Transactional
    public ResponseEntity<AdminAlertResponse> resolve(
            @PathVariable UUID id,
            @RequestBody ResolveAlertRequest request) {
        AdminAlertEntity alert = alertRepo.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "alert-not-found", "Not Found", "Alerte introuvable"));
        alert.setResolved(true);
        alert.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        alertRepo.save(alert);
        return ResponseEntity.ok(AdminAlertResponse.from(alert));
    }
}
