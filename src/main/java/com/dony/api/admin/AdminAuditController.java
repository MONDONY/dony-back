package com.dony.api.admin;

import com.dony.api.admin.dto.AdminAuditEntryResponse;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditLogRepository;
import com.dony.api.common.MatchingTextUtil;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

        Page<com.dony.api.common.AuditLogEntity> auditPage = auditRepo
            .findFiltered(action, entityType, actorId, from, to,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        // Batch-load all actor names in a single query — avoids N+1
        Set<UUID> actorIds = auditPage.getContent().stream()
            .map(com.dony.api.common.AuditLogEntity::getActorId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
        Map<UUID, String> actorNames = userRepo.findAllById(actorIds).stream()
            .collect(Collectors.toMap(
                UserEntity::getId,
                MatchingTextUtil::buildName
            ));

        Page<AdminAuditEntryResponse> result = auditPage.map(e ->
            AdminAuditEntryResponse.from(e, actorNames.get(e.getActorId()))
        );
        return ResponseEntity.ok(result);
    }
}
