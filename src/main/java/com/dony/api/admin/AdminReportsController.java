package com.dony.api.admin;

import com.dony.api.admin.dto.AdminReportResponse;
import com.dony.api.admin.dto.ResolveReportRequest;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.signalements.ReportEntity;
import com.dony.api.signalements.ReportRepository;
import com.dony.api.signalements.ReportStatus;
import com.dony.api.signalements.ReportTargetType;
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
import java.util.Map;
import java.util.UUID;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportsController {

    private final ReportRepository reportRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;

    public AdminReportsController(ReportRepository reportRepo,
                                  UserRepository userRepo,
                                  AuditService auditService) {
        this.reportRepo = reportRepo;
        this.userRepo = userRepo;
        this.auditService = auditService;
    }

    @GetMapping("/admin/reports")
    public ResponseEntity<Page<AdminReportResponse>> listReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportTargetType targetType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<ReportEntity> entities = reportRepo.findFiltered(
                status, targetType,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        Page<AdminReportResponse> result = entities.map(this::toResponse);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/admin/reports/{id}/resolve")
    @Transactional
    public ResponseEntity<AdminReportResponse> resolveReport(
            @PathVariable UUID id,
            @RequestBody ResolveReportRequest request) {

        ReportEntity report = reportRepo.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "report-not-found", "Not Found", "Signalement introuvable"));

        report.setStatus(ReportStatus.RESOLVED);
        report.setActionTaken(request.action());
        report.setResolutionNote(request.note());
        report.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        reportRepo.save(report);

        auditService.log("REPORT", id, "REPORT_RESOLVED", null,
                Map.of(
                        "reportId", id.toString(),
                        "action", request.action() != null ? request.action() : "",
                        "note", request.note() != null ? request.note() : ""
                ));

        return ResponseEntity.ok(toResponse(report));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AdminReportResponse toResponse(ReportEntity r) {
        String reporterName = resolveReporterName(r.getReporterId());
        return new AdminReportResponse(
                r.getId(),
                r.getTargetType() != null ? r.getTargetType().name() : null,
                r.getTargetId(),
                reporterName,
                r.getReason(),
                r.getDescription(),
                r.getStatus() != null ? r.getStatus().name() : null,
                r.getActionTaken(),
                r.getResolutionNote(),
                r.getResolvedAt(),
                r.getCreatedAt()
        );
    }

    private String resolveReporterName(UUID reporterId) {
        if (reporterId == null) return null;
        return userRepo.findById(reporterId)
                .map(u -> u.getFirstName() + (u.getLastName() != null ? " " + u.getLastName() : ""))
                .orElse(null);
    }
}
