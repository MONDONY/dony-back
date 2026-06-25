package com.dony.api.admin;

import com.dony.api.admin.dto.AdminCancellationResponse;
import com.dony.api.admin.dto.AdminDisputeDetailResponse;
import com.dony.api.admin.dto.AdminDisputeListItemResponse;
import com.dony.api.admin.dto.AdminGuaranteeFundRequest;
import com.dony.api.admin.dto.AdminResolveDisputeRequest;
import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.CancellationEntity;
import com.dony.api.cancellation.CancellationRepository;
import com.dony.api.cancellation.CancellationStatus;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.MatchingTextUtil;
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

import com.dony.api.auth.UserEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminDisputesController {

    private final DisputeRepository disputeRepo;
    private final CancellationRepository cancellationRepo;
    private final AuditService auditService;
    private final UserRepository userRepo;

    public AdminDisputesController(DisputeRepository disputeRepo,
                                   CancellationRepository cancellationRepo,
                                   AuditService auditService,
                                   UserRepository userRepo) {
        this.disputeRepo = disputeRepo;
        this.cancellationRepo = cancellationRepo;
        this.auditService = auditService;
        this.userRepo = userRepo;
    }

    // -------------------------------------------------------------------------
    // Disputes
    // -------------------------------------------------------------------------

    @GetMapping("/admin/disputes")
    public ResponseEntity<Page<AdminDisputeListItemResponse>> listDisputes(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<DisputeEntity> disputes = disputeRepo
                .findAdminFiltered(status, PageRequest.of(page, size, Sort.by("createdAt").descending()));

        Set<UUID> userIds = new HashSet<>();
        for (DisputeEntity d : disputes.getContent()) {
            if (d.getSenderId() != null) userIds.add(d.getSenderId());
            if (d.getTravelerId() != null) userIds.add(d.getTravelerId());
        }
        Map<UUID, UserEntity> usersById = userRepo.findAllById(userIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));

        Page<AdminDisputeListItemResponse> result = disputes.map(d -> toDisputeListItem(d, usersById));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/admin/disputes/{id}")
    public ResponseEntity<AdminDisputeDetailResponse> getDispute(@PathVariable UUID id) {
        DisputeEntity entity = findDisputeOrThrow(id);
        Set<UUID> ids = new HashSet<>();
        if (entity.getSenderId() != null) ids.add(entity.getSenderId());
        if (entity.getTravelerId() != null) ids.add(entity.getTravelerId());
        Map<UUID, UserEntity> usersById = userRepo.findAllById(ids).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(toDisputeDetail(entity, usersById));
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
                Map.of("resolution", Objects.toString(request.resolution(), ""),
                       "note", Objects.toString(request.note(), "")));

        Set<UUID> resolveIds = new HashSet<>();
        if (entity.getSenderId() != null) resolveIds.add(entity.getSenderId());
        if (entity.getTravelerId() != null) resolveIds.add(entity.getTravelerId());
        Map<UUID, UserEntity> resolveUsers = userRepo.findAllById(resolveIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(toDisputeDetail(entity, resolveUsers));
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
                       "beneficiaryUserId", Objects.toString(request.beneficiaryUserId() != null ? request.beneficiaryUserId().toString() : null, ""),
                       "reason", Objects.toString(request.reason(), "")));

        Set<UUID> gfIds = new HashSet<>();
        if (entity.getSenderId() != null) gfIds.add(entity.getSenderId());
        if (entity.getTravelerId() != null) gfIds.add(entity.getTravelerId());
        Map<UUID, UserEntity> gfUsers = userRepo.findAllById(gfIds).stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));
        return ResponseEntity.ok(toDisputeDetail(entity, gfUsers));
    }

    // -------------------------------------------------------------------------
    // Cancellations
    // -------------------------------------------------------------------------

    @GetMapping("/admin/cancellations")
    public ResponseEntity<Page<AdminCancellationResponse>> listCancellations(
            @RequestParam(required = false) CancellationStatus noShowStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<AdminCancellationResponse> result = cancellationRepo
                .findAdminFiltered(noShowStatus, PageRequest.of(page, size, Sort.by("createdAt").descending()))
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

    private String userName(UUID userId, Map<UUID, UserEntity> users) {
        if (userId == null) return null;
        UserEntity u = users.get(userId);
        if (u == null) return null;
        return MatchingTextUtil.buildName(u);
    }

    private AdminDisputeListItemResponse toDisputeListItem(DisputeEntity d, Map<UUID, UserEntity> users) {
        return new AdminDisputeListItemResponse(
                d.getId(),
                d.getBidId(),
                d.getType(),
                d.getStatus(),
                userName(d.getSenderId(), users),
                userName(d.getTravelerId(), users),
                d.isRefundFrozen(),
                d.getCreatedAt());
    }

    private AdminDisputeDetailResponse toDisputeDetail(DisputeEntity d, Map<UUID, UserEntity> users) {
        return new AdminDisputeDetailResponse(
                d.getId(),
                d.getBidId(),
                d.getType(),
                d.getStatus(),
                userName(d.getSenderId(), users),
                userName(d.getTravelerId(), users),
                d.isRefundFrozen(),
                d.getCreatedAt(),
                d.getResolutionType(),
                d.getResolvedAt(),
                d.getResolutionNote(),
                d.getDeclaredValueEur(),
                d.getBeneficiaryUserId());
    }

    private AdminCancellationResponse toCancellationResponse(CancellationEntity e) {
        return new AdminCancellationResponse(
                e.getId(),
                e.getBidId(),
                e.getCancelledBy(),
                e.getReason(),
                e.getNoShowStatus() != null ? e.getNoShowStatus().name() : null,
                e.getContestationDeadline(),
                e.getCreatedAt());
    }
}
