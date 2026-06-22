package com.dony.api.admin.incidents;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.CancellationEntity;
import com.dony.api.cancellation.CancellationRepository;
import com.dony.api.cancellation.CancellationStatus;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.disputes.DisputeEntity;
import com.dony.api.disputes.DisputeRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminIncidentsService {

    private final DisputeRepository disputeRepository;
    private final CancellationRepository cancellationRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final EntityManager em;

    public AdminIncidentsService(DisputeRepository disputeRepository,
                                 CancellationRepository cancellationRepository,
                                 BidRepository bidRepository,
                                 UserRepository userRepository,
                                 AuditService auditService,
                                 EntityManager em) {
        this.disputeRepository = disputeRepository;
        this.cancellationRepository = cancellationRepository;
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.em = em;
    }

    // -------------------------------------------------------------------------
    // Disputes — list
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdminDisputeSummary> listDisputes(String status, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<DisputeEntity> q = cb.createQuery(DisputeEntity.class);
        Root<DisputeEntity> root = q.from(DisputeEntity.class);
        List<Predicate> predicates = new ArrayList<>();
        if (status != null) predicates.add(cb.equal(root.get("status"), status));
        q.select(root).where(predicates.toArray(new Predicate[0])).orderBy(cb.desc(root.get("createdAt")));

        List<DisputeEntity> disputes = em.createQuery(q)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<DisputeEntity> cr = cq.from(DisputeEntity.class);
        List<Predicate> cp = new ArrayList<>();
        if (status != null) cp.add(cb.equal(cr.get("status"), status));
        cq.select(cb.count(cr)).where(cp.toArray(new Predicate[0]));
        long total = em.createQuery(cq).getSingleResult();

        List<AdminDisputeSummary> content = disputes.stream()
                .map(d -> AdminDisputeSummary.from(d, userName(d.getSenderId()), userName(d.getTravelerId())))
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    // -------------------------------------------------------------------------
    // Disputes — get
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AdminDisputeDetailResponse getDispute(UUID id) {
        DisputeEntity d = findDisputeOrThrow(id);
        BigDecimal declaredValue = bidRepository.findById(d.getBidId())
                .map(BidEntity::getDeclaredValueEur).orElse(null);
        return AdminDisputeDetailResponse.from(d, userName(d.getSenderId()), userName(d.getTravelerId()), declaredValue);
    }

    // -------------------------------------------------------------------------
    // Disputes — resolve
    // -------------------------------------------------------------------------

    @Transactional
    public AdminDisputeDetailResponse resolve(UUID id, String resolution, String note, UUID actorId) {
        DisputeEntity d = findDisputeOrThrow(id);
        if ("RESOLVED".equals(d.getStatus())) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "dispute-already-resolved",
                    "Conflict", "Ce litige est déjà résolu");
        }
        d.setStatus("RESOLVED");
        d.setResolution(resolution);
        d.setResolutionNote(note);
        d.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        disputeRepository.save(d);

        auditService.log("DISPUTE", id, "DISPUTE_RESOLVED_BY_ADMIN", actorId,
                Map.of("resolution", resolution, "note", note != null ? note : ""));

        BigDecimal declaredValue = bidRepository.findById(d.getBidId())
                .map(BidEntity::getDeclaredValueEur).orElse(null);
        return AdminDisputeDetailResponse.from(d, userName(d.getSenderId()), userName(d.getTravelerId()), declaredValue);
    }

    // -------------------------------------------------------------------------
    // Disputes — guarantee fund
    // -------------------------------------------------------------------------

    @Transactional
    public AdminDisputeDetailResponse payGuaranteeFund(UUID id, int amountCents, UUID beneficiaryUserId, String reason, UUID actorId) {
        DisputeEntity d = findDisputeOrThrow(id);
        d.setStatus("RESOLVED");
        d.setResolution("GUARANTEE_PAID");
        d.setResolutionNote(reason);
        d.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        d.setBeneficiaryUserId(beneficiaryUserId);
        disputeRepository.save(d);

        auditService.log("DISPUTE", id, "GUARANTEE_FUND_PAID_BY_ADMIN", actorId,
                Map.of("amountCents", String.valueOf(amountCents),
                       "beneficiaryUserId", beneficiaryUserId.toString(),
                       "reason", reason != null ? reason : ""));

        BigDecimal declaredValue = bidRepository.findById(d.getBidId())
                .map(BidEntity::getDeclaredValueEur).orElse(null);
        return AdminDisputeDetailResponse.from(d, userName(d.getSenderId()), userName(d.getTravelerId()), declaredValue);
    }

    // -------------------------------------------------------------------------
    // Cancellations — list
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdminCancellationResponse> listCancellations(String noShowStatus, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<CancellationEntity> q = cb.createQuery(CancellationEntity.class);
        Root<CancellationEntity> root = q.from(CancellationEntity.class);
        List<Predicate> predicates = new ArrayList<>();
        if (noShowStatus != null) {
            predicates.add(cb.equal(root.get("noShowStatus"), CancellationStatus.valueOf(noShowStatus)));
        }
        q.select(root).where(predicates.toArray(new Predicate[0])).orderBy(cb.desc(root.get("createdAt")));

        List<CancellationEntity> cancellations = em.createQuery(q)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<CancellationEntity> cr = cq.from(CancellationEntity.class);
        List<Predicate> cp = new ArrayList<>();
        if (noShowStatus != null) cp.add(cb.equal(cr.get("noShowStatus"), CancellationStatus.valueOf(noShowStatus)));
        cq.select(cb.count(cr)).where(cp.toArray(new Predicate[0]));
        long total = em.createQuery(cq).getSingleResult();

        return new PageImpl<>(cancellations.stream().map(AdminCancellationResponse::from).toList(), pageable, total);
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private DisputeEntity findDisputeOrThrow(UUID id) {
        return disputeRepository.findById(id)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "dispute-not-found", "Not Found", "Litige introuvable"));
    }

    private String userName(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(u -> {
                    String name = "";
                    if (u.getFirstName() != null) name += u.getFirstName();
                    if (u.getLastName() != null) name += (name.isEmpty() ? "" : " ") + u.getLastName();
                    return name.isBlank() ? u.getPhoneNumber() : name;
                })
                .orElse(null);
    }
}
