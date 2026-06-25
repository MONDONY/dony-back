package com.dony.api.admin;

import com.dony.api.admin.dto.AdminAuditEntryResponse;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditController {

    private final AuditLogRepository auditRepo;
    private final UserRepository userRepo;

    public AdminAuditController(AuditLogRepository auditRepo, UserRepository userRepo) {
        this.auditRepo = auditRepo;
        this.userRepo = userRepo;
    }

    @GetMapping
    public ResponseEntity<Page<AdminAuditEntryResponse>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID actorUuid = actorId != null ? UUID.fromString(actorId) : null;
        Page<AdminAuditEntryResponse> result = auditRepo
            .findFiltered(action, entityType, actorUuid, from, to,
                PageRequest.of(page, size, Sort.by("createdAt").descending()))
            .map(e -> {
                String name = null;
                if (e.getActorId() != null) {
                    name = userRepo.findById(e.getActorId())
                        .map(u -> u.getFirstName() + (u.getLastName() != null ? " " + u.getLastName() : ""))
                        .orElse(null);
                }
                return AdminAuditEntryResponse.from(e, name);
            });
        return ResponseEntity.ok(result);
    }
}
