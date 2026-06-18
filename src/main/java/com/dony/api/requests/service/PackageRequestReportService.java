package com.dony.api.requests.service;

import com.dony.api.common.AuditService;
import com.dony.api.requests.dto.PackageRequestReportRequest;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestReportEntity;
import com.dony.api.requests.repository.PackageRequestReportRepository;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/** Signalement (modération) d'une demande d'envoi. */
@Service
public class PackageRequestReportService {

    private final PackageRequestRepository requestRepository;
    private final PackageRequestReportRepository reportRepository;
    private final AuditService auditService;

    public PackageRequestReportService(PackageRequestRepository requestRepository,
                                       PackageRequestReportRepository reportRepository,
                                       AuditService auditService) {
        this.requestRepository = requestRepository;
        this.reportRepository = reportRepository;
        this.auditService = auditService;
    }

    /**
     * Signale une demande. Idempotent par couple (demande, reporter) — re-signaler ne crée
     * pas de doublon. L'auto-signalement (signaler sa propre demande) est interdit (422).
     */
    @Transactional
    public void report(UUID reporterId, UUID requestId, PackageRequestReportRequest req) {
        PackageRequestEntity entity = requestRepository.findById(requestId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "request/not-found"));
        if (entity.getSenderId().equals(reporterId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "request/cannot-report-own");
        }
        if (reportRepository.existsByPackageRequestIdAndReporterId(requestId, reporterId)) {
            return; // déjà signalé — idempotent
        }
        reportRepository.save(new PackageRequestReportEntity(requestId, reporterId, req.reason(), req.details()));
        auditService.log("PACKAGE_REQUEST", requestId, "REPORTED", reporterId,
            Map.of("reason", req.reason()));
    }
}
