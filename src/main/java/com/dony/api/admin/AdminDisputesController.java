package com.dony.api.admin;

import com.dony.api.admin.dto.AdminCancellationResponse;
import com.dony.api.admin.dto.AdminDisputeDetailResponse;
import com.dony.api.admin.dto.AdminDisputeListItemResponse;
import com.dony.api.admin.dto.AdminGuaranteeFundRequest;
import com.dony.api.admin.dto.AdminResolveDisputeRequest;
import com.dony.api.cancellation.CancellationEntity;
import com.dony.api.cancellation.CancellationRepository;
import com.dony.api.cancellation.CancellationStatus;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.disputes.DisputeEntity;
import com.dony.api.disputes.DisputeRepository;
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
public class AdminDisputesController {

    private final DisputeRepository disputeRepo;
    private final CancellationRepository cancellationRepo;
    private final AuditService auditService;

    public AdminDisputesController(DisputeRepository disputeRepo,
                                   CancellationRepository cancellationRepo,
                                   AuditService auditService) {
        this.disputeRepo = disputeRepo;
        this.cancellationRepo = cancellationRepo;
        this.auditService = auditService;
    }

    // -------------------------------------------------------------------------
    // Disputes
    // -------------------------------------------------------------------------

    @GetMapping("/admin/disputes")
    public ResponseEntity<Page<AdminDisputeListItemResponse>> listDisputes(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<AdminDisputeListItemResponse> result = disputeRepo
                .findAdminFiltered(status, PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(this::toListItem);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/admin/disputes/{id}")
    public ResponseEntity<AdminDisputeDetailResponse> getDispute(@PathVariable UUID id) {
        DisputeEntity entity = findDisputeOrThrow(id);
        return ResponseEntity.ok(toDetail(entity));
    }

    @PostMapping("/admin/disputes/{id}/resolve")
    @Transactional
    public ResponseEntity<AdminDisputeDetailResponse> resolveDispute(
            @PathVariable UUID id,
            @RequestBody AdminResolveDisputeRequest request) {

        DisputeEntity entity = findDisputeOrThrow(id);
        entity.setStatus("RESOLVED");
        entity.setResolutionType(request.resolution());
        entity.setResolutionNote(request.note());
        entity.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        disputeRepo.save(entity);

        auditService.log("DISPUTE", entity.getId(), "RESOLVE", null,
                Map.of("resolution", String.valueOf(request.resolution()),
                       "note", String.valueOf(request.note())));

        return ResponseEntity.ok(toDetail(entity));
    }

    @PostMapping("/admin/disputes/{id}/guarantee-fund")
    @Transactional
    public ResponseEntity<AdminDisputeDetailResponse> payGuaranteeFund(
            @PathVariable UUID id,
            @RequestBody AdminGuaranteeFundRequest request) {

        DisputeEntity entity = findDisputeOrThrow(id);
        entity.setStatus("RESOLVED");
        entity.setResolutionType("GUARANTEE_PAID");
        entity.setResolutionNote(request.reason());
        entity.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setBeneficiaryUserId(request.beneficiaryUserId());
        disputeRepo.save(entity);

        auditService.log("DISPUTE", entity.getId(), "GUARANTEE_FUND", null,
                Map.of("amountCents", request.amountCents(),
                       "beneficiaryUserId", String.valueOf(request.beneficiaryUserId()),
                       "reason", String.valueOf(request.reason())));

        return ResponseEntity.ok(toDetail(entity));
    }

    // -------------------------------------------------------------------------
    // Cancellations
    // -------------------------------------------------------------------------

    @GetMapping("/admin/cancellations")
    public ResponseEntity<Page<AdminCancellationResponse>> listCancellations(
            @RequestParam(required = false) String noShowStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        CancellationStatus statusEnum = noShowStatus != null
                ? CancellationStatus.valueOf(noShowStatus) : null;

        Page<AdminCancellationResponse> result = cancellationRepo
                .findAdminFiltered(statusEnum, PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(this::toCancellationResponse);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private DisputeEntity findDisputeOrThrow(UUID id) {
        return disputeRepo.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "dispute-not-found", "Not Found", "Litige introuvable"));
    }

    private AdminDisputeListItemResponse toListItem(DisputeEntity e) {
        return new AdminDisputeListItemResponse(
                e.getId(),
                e.getBidId(),
                e.getSenderId(),
                e.getTravelerId(),
                e.getType(),
                e.getStatus(),
                e.isRefundFrozen(),
                e.getDeclaredValueEur(),
                e.getCreatedAt());
    }

    private AdminDisputeDetailResponse toDetail(DisputeEntity e) {
        return new AdminDisputeDetailResponse(
                e.getId(),
                e.getBidId(),
                e.getSenderId(),
                e.getTravelerId(),
                e.getType(),
                e.getStatus(),
                e.isRefundFrozen(),
                e.getResolutionType(),
                e.getResolvedAt(),
                e.getResolutionNote(),
                e.getDeclaredValueEur(),
                e.getBeneficiaryUserId(),
                e.getCreatedAt());
    }

    private AdminCancellationResponse toCancellationResponse(CancellationEntity e) {
        return new AdminCancellationResponse(
                e.getId(),
                e.getBidId(),
                e.getCancelledBy(),
                e.getReason(),
                e.getRefundStatus(),
                e.getRematchStatus(),
                e.getNoShowStatus() != null ? e.getNoShowStatus().name() : null,
                e.getContestationDeadline(),
                e.getCreatedAt());
    }
}
