package com.dony.api.admin.users;

import com.dony.api.auth.KycStatus;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.auth.UserStatus;
import com.dony.api.auth.events.UserSuspendedEvent;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager em;

    public AdminUserService(UserRepository userRepository,
                            AuditService auditService,
                            ApplicationEventPublisher eventPublisher,
                            EntityManager em) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.em = em;
    }

    // -------------------------------------------------------------------------
    // List
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdminUserSummary> list(AdminUserFilter filter, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<UserEntity> query = cb.createQuery(UserEntity.class);
        Root<UserEntity> root = query.from(UserEntity.class);
        query.select(root).where(buildPredicates(cb, root, filter)).orderBy(cb.desc(root.get("createdAt")));

        TypedQuery<UserEntity> tq = em.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize());

        List<AdminUserSummary> content = tq.getResultList().stream()
                .map(AdminUserSummary::from).toList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<UserEntity> countRoot = countQuery.from(UserEntity.class);
        countQuery.select(cb.count(countRoot)).where(buildPredicates(cb, countRoot, filter));
        long total = em.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    private Predicate[] buildPredicates(CriteriaBuilder cb, Root<UserEntity> root, AdminUserFilter f) {
        List<Predicate> predicates = new ArrayList<>();

        if (f.status() != null) {
            predicates.add(cb.equal(root.get("status"), UserStatus.valueOf(f.status())));
        }
        if (f.kyc() != null) {
            predicates.add(cb.equal(root.get("kycStatus"), KycStatus.valueOf(f.kyc())));
        }
        if (f.pro() != null) {
            predicates.add(cb.equal(root.get("isProAccount"), f.pro()));
        }
        if (f.city() != null && !f.city().isBlank()) {
            predicates.add(cb.like(cb.lower(root.get("city")), "%" + f.city().toLowerCase() + "%"));
        }
        if (f.query() != null && !f.query().isBlank()) {
            String like = "%" + f.query().toLowerCase() + "%";
            predicates.add(cb.or(
                    cb.like(cb.lower(root.get("firstName")), like),
                    cb.like(cb.lower(root.get("lastName")), like),
                    cb.like(root.get("phoneNumber"), "%" + f.query() + "%")
            ));
        }
        if (f.role() != null) {
            Join<UserEntity, Role> roleJoin = root.join("roles", JoinType.INNER);
            predicates.add(cb.equal(roleJoin, Role.valueOf(f.role())));
        }

        return predicates.toArray(new Predicate[0]);
    }

    // -------------------------------------------------------------------------
    // Get
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AdminUserDetailResponse get(UUID userId) {
        return AdminUserDetailResponse.from(findOrThrow(userId));
    }

    // -------------------------------------------------------------------------
    // Suspend
    // -------------------------------------------------------------------------

    @Transactional
    public AdminUserDetailResponse suspend(UUID userId, String reason, UUID actorId) {
        UserEntity user = findOrThrow(userId);

        if (user.getStatus() == UserStatus.BANNED) {
            throw new DonyBusinessException(HttpStatus.CONFLICT, "user-already-banned",
                    "Conflict", "Cet utilisateur est déjà banni");
        }

        user.setStatus(UserStatus.SUSPENDED);
        userRepository.save(user);

        auditService.log("USER", userId, "USER_SUSPENDED_BY_ADMIN", actorId,
                Map.of("reason", reason != null ? reason : ""));
        eventPublisher.publishEvent(new UserSuspendedEvent(
                userId, user.getPhoneNumber(), user.getEmail(),
                reason != null ? reason : "Suspension administrative"
        ));

        return AdminUserDetailResponse.from(user);
    }

    // -------------------------------------------------------------------------
    // Ban
    // -------------------------------------------------------------------------

    @Transactional
    public AdminUserDetailResponse ban(UUID userId, String reason, UUID actorId) {
        UserEntity user = findOrThrow(userId);

        user.setStatus(UserStatus.BANNED);
        userRepository.save(user);

        auditService.log("USER", userId, "USER_BANNED_BY_ADMIN", actorId,
                Map.of("reason", reason != null ? reason : ""));

        return AdminUserDetailResponse.from(user);
    }

    // -------------------------------------------------------------------------
    // Unsuspend (wrap existing)
    // -------------------------------------------------------------------------

    @Transactional
    public AdminUserDetailResponse unsuspend(UUID userId, UUID actorId) {
        UserEntity user = findOrThrow(userId);

        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        auditService.log("USER", userId, "USER_UNSUSPENDED", actorId, Map.of());

        return AdminUserDetailResponse.from(user);
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private UserEntity findOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));
    }
}
