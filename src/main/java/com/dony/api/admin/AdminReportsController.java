package com.dony.api.admin;

import com.dony.api.admin.dto.AdminReportResponse;
import com.dony.api.admin.dto.ResolveReportRequest;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.MatchingTextUtil;
import com.dony.api.signalements.ReportEntity;
import com.dony.api.signalements.ReportRepository;
import com.dony.api.signalements.ReportService;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportsController {

    private final ReportRepository reportRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;
    private final ReportService reportService;

    public AdminReportsController(ReportRepository reportRepo,
                                  UserRepository userRepo,
                                  AuditService auditService,
                                  ReportService reportService) {
        this.reportRepo = reportRepo;
        this.userRepo = userRepo;
        this.auditService = auditService;
        this.reportService = reportService;
    }

    @GetMapping("/admin/reports")
    public ResponseEntity<Page<AdminReportResponse>> listReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportTargetType targetType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<ReportEntity> reports = reportRepo.findFiltered(
                status, targetType,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        Set<UUID> reporterIds = reports.getContent().stream()
                .map(ReportEntity::getReporterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UserEntity> usersById = userRepo.findAllById(reporterIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));

        Map<UUID, List<String>> photosByReport = reportService.photoUrlsByReport(
                reports.getContent().stream().map(ReportEntity::getId).collect(Collectors.toSet()));

        Page<AdminReportResponse> result = reports.map(r ->
                toResponse(r, usersById, photosByReport.getOrDefault(r.getId(), List.of())));
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

        Map<UUID, UserEntity> singleUser = userRepo.findAllById(
                report.getReporterId() != null ? Set.of(report.getReporterId()) : Set.of()).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(toResponse(report, singleUser, reportService.photoUrls(report.getId())));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AdminReportResponse toResponse(ReportEntity r, Map<UUID, UserEntity> users, List<String> photoUrls) {
        String reporterName = resolveReporterName(r.getReporterId(), users);
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
                r.getCreatedAt(),
                photoUrls
        );
    }

    private String resolveReporterName(UUID reporterId, Map<UUID, UserEntity> users) {
        if (reporterId == null) return null;
        UserEntity u = users.get(reporterId);
        if (u == null) return null;
        return MatchingTextUtil.buildName(u);
    }
}
