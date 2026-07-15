package com.dony.api.disputes;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.MatchingTextUtil;
import com.dony.api.disputes.dto.DisputeResponse;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class DisputeService {

    private static final String TYPE_NO_SHOW = "SENDER_NO_SHOW_CONTESTED";
    private static final String STATUS_OPEN  = "OPEN";

    private final DisputeRepository disputeRepository;
    private final AuditService auditService;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;

    public DisputeService(DisputeRepository disputeRepository, AuditService auditService,
                          BidRepository bidRepository, AnnouncementRepository announcementRepository,
                          UserRepository userRepository) {
        this.disputeRepository = disputeRepository;
        this.auditService = auditService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
    }

    public DisputeEntity openSenderNoShowDispute(UUID bidId, UUID senderId, UUID travelerId) {
        Optional<DisputeEntity> existing = disputeRepository.findByBidId(bidId);
        if (existing.isPresent()) {
            return existing.get();
        }

        DisputeEntity dispute = new DisputeEntity();
        dispute.setBidId(bidId);
        dispute.setSenderId(senderId);
        dispute.setTravelerId(travelerId);
        dispute.setType(TYPE_NO_SHOW);
        dispute.setStatus(STATUS_OPEN);
        dispute.setRefundFrozen(true);

        DisputeEntity saved = disputeRepository.save(dispute);

        auditService.log("DISPUTE", saved.getId(), "SENDER_NO_SHOW_DISPUTE_OPENED", senderId,
                Map.of("bidId", bidId.toString(), "travelerId", travelerId.toString(),
                       "type", TYPE_NO_SHOW));

        return saved;
    }

    /** Litiges où l'utilisateur est sender OU traveler, plus récents d'abord. */
    @Transactional(readOnly = true)
    public List<DisputeResponse> getDisputesForUser(UUID userId) {
        List<DisputeEntity> disputes = disputeRepository
                .findBySenderIdOrTravelerIdOrderByCreatedAtDesc(userId, userId);

        Set<UUID> bidIds = disputes.stream().map(DisputeEntity::getBidId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, BidEntity> bids = bidRepository.findAllById(bidIds).stream()
                .collect(Collectors.toMap(BidEntity::getId, Function.identity(), (a, b) -> a));

        Set<UUID> annIds = bids.values().stream().map(BidEntity::getAnnouncementId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, AnnouncementEntity> anns = announcementRepository.findAllById(annIds).stream()
                .collect(Collectors.toMap(AnnouncementEntity::getId, Function.identity(), (a, b) -> a));

        Set<UUID> otherIds = new HashSet<>();
        for (DisputeEntity d : disputes) {
            UUID other = userId.equals(d.getSenderId()) ? d.getTravelerId() : d.getSenderId();
            if (other != null) otherIds.add(other);
        }
        Map<UUID, UserEntity> users = userRepository.findAllById(otherIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (a, b) -> a));

        return disputes.stream().map(d -> toResponse(d, userId, bids, anns, users)).toList();
    }

    private DisputeResponse toResponse(DisputeEntity d, UUID userId,
            Map<UUID, BidEntity> bids, Map<UUID, AnnouncementEntity> anns,
            Map<UUID, UserEntity> users) {
        boolean isSender = userId.equals(d.getSenderId());
        UUID otherId = isSender ? d.getTravelerId() : d.getSenderId();
        UserEntity other = otherId != null ? users.get(otherId) : null;
        BidEntity bid = d.getBidId() != null ? bids.get(d.getBidId()) : null;
        AnnouncementEntity ann = (bid != null && bid.getAnnouncementId() != null)
                ? anns.get(bid.getAnnouncementId()) : null;

        return new DisputeResponse(
                d.getId(), d.getBidId(), d.getType(), d.getStatus(), d.isRefundFrozen(),
                d.getCreatedAt(),
                isSender ? "SENDER" : "TRAVELER",
                other != null ? MatchingTextUtil.buildName(other) : null,
                ann != null ? ann.getDepartureCity() : null,
                ann != null ? ann.getArrivalCity() : null,
                ann != null ? ann.getDepartureCountryCode() : null,
                ann != null ? ann.getArrivalCountryCode() : null,
                ann != null ? ann.getDepartureDate() : null,
                bid != null ? bid.getWeightKg() : null,
                d.getResolutionType(), d.getResolvedAt(), d.getResolutionNote(),
                d.getGuaranteeAmountCents(),
                userId.equals(d.getBeneficiaryUserId()));
    }
}
