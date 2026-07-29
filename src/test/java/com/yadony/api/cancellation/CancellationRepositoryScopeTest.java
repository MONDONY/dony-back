package com.yadony.api.cancellation;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class CancellationRepositoryScopeTest {

    @Autowired CancellationRepository cancellationRepository;
    @Autowired BidRepository bidRepository;

    @Test
    void aBidCanHaveOneHandoverAndOneDeliveryCancellation() {
        BidEntity bid = persistBid();

        CancellationEntity handover = new CancellationEntity();
        handover.setBidId(bid.getId());
        handover.setCancelledBy(bid.getSenderId());
        handover.setReason("SENDER_NO_SHOW");
        handover.setScope(CancellationScope.HANDOVER);
        cancellationRepository.save(handover);

        CancellationEntity delivery = new CancellationEntity();
        delivery.setBidId(bid.getId());
        delivery.setCancelledBy(bid.getSenderId());
        delivery.setReason("RECIPIENT_NO_SHOW");
        delivery.setScope(CancellationScope.DELIVERY);
        cancellationRepository.save(delivery);

        assertThat(cancellationRepository.findByBidId(bid.getId())).isPresent();
        assertThat(cancellationRepository.findByBidId(bid.getId()).get().getScope())
                .isEqualTo(CancellationScope.HANDOVER);
        assertThat(cancellationRepository.findByBidIdAndScope(bid.getId(), CancellationScope.DELIVERY))
                .isPresent();
        assertThat(cancellationRepository.findByBidIdAndScope(bid.getId(), CancellationScope.DELIVERY).get().getReason())
                .isEqualTo("RECIPIENT_NO_SHOW");
    }

    @Test
    void existsByBidIdAndScopeAndNoShowStatusIn_filtersByScope() {
        BidEntity bid = persistBid();
        CancellationEntity delivery = new CancellationEntity();
        delivery.setBidId(bid.getId());
        delivery.setCancelledBy(bid.getSenderId());
        delivery.setReason("RECIPIENT_NO_SHOW");
        delivery.setScope(CancellationScope.DELIVERY);
        delivery.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        cancellationRepository.save(delivery);

        assertThat(cancellationRepository.existsByBidIdAndScopeAndNoShowStatusIn(
                bid.getId(), CancellationScope.DELIVERY,
                List.of(CancellationStatus.PENDING_CONFIRMATION))).isTrue();
        assertThat(cancellationRepository.existsByBidIdAndScopeAndNoShowStatusIn(
                bid.getId(), CancellationScope.HANDOVER,
                List.of(CancellationStatus.PENDING_CONFIRMATION))).isFalse();
    }

    @Test
    void findExpiredPendingByScope_onlyReturnsMatchingScope() {
        BidEntity bid = persistBid();
        CancellationEntity delivery = new CancellationEntity();
        delivery.setBidId(bid.getId());
        delivery.setCancelledBy(bid.getSenderId());
        delivery.setReason("RECIPIENT_NO_SHOW");
        delivery.setScope(CancellationScope.DELIVERY);
        delivery.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        delivery.setContestationDeadline(OffsetDateTime.now().minusHours(1));
        cancellationRepository.save(delivery);

        List<CancellationEntity> expiredDelivery = cancellationRepository
                .findExpiredPendingByScope(CancellationScope.DELIVERY, OffsetDateTime.now());
        List<CancellationEntity> expiredHandover = cancellationRepository
                .findExpiredPendingByScope(CancellationScope.HANDOVER, OffsetDateTime.now());

        assertThat(expiredDelivery).hasSize(1);
        assertThat(expiredHandover).isEmpty();
    }

    @Test
    void existsByBidIdAndNoShowStatusIn_legacyMethod_ignoresDeliveryScopeRows() {
        BidEntity bid = persistBid();

        // Ce cancellation DELIVERY matcherait le statut recherché si le filtre
        // scope='HANDOVER' du @Query legacy était absent ou mal appliqué.
        CancellationEntity delivery = new CancellationEntity();
        delivery.setBidId(bid.getId());
        delivery.setCancelledBy(bid.getSenderId());
        delivery.setReason("RECIPIENT_NO_SHOW");
        delivery.setScope(CancellationScope.DELIVERY);
        delivery.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        cancellationRepository.save(delivery);

        assertThat(cancellationRepository.existsByBidIdAndNoShowStatusIn(
                bid.getId(), List.of(CancellationStatus.PENDING_CONFIRMATION))).isFalse();

        // Preuve positive que la méthode fonctionne bien quand la ligne est HANDOVER.
        CancellationEntity handover = new CancellationEntity();
        handover.setBidId(bid.getId());
        handover.setCancelledBy(bid.getSenderId());
        handover.setReason("SENDER_NO_SHOW");
        handover.setScope(CancellationScope.HANDOVER);
        handover.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        cancellationRepository.save(handover);

        assertThat(cancellationRepository.existsByBidIdAndNoShowStatusIn(
                bid.getId(), List.of(CancellationStatus.PENDING_CONFIRMATION))).isTrue();
    }

    @Test
    void findExpiredPending_legacyMethod_ignoresDeliveryScopeRows() {
        BidEntity bid = persistBid();

        // Deadline expirée + statut PENDING_CONFIRMATION : matcherait
        // findExpiredPending() si le filtre scope='HANDOVER' n'était pas
        // appliqué par le @Query legacy.
        CancellationEntity delivery = new CancellationEntity();
        delivery.setBidId(bid.getId());
        delivery.setCancelledBy(bid.getSenderId());
        delivery.setReason("RECIPIENT_NO_SHOW");
        delivery.setScope(CancellationScope.DELIVERY);
        delivery.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        delivery.setContestationDeadline(OffsetDateTime.now().minusHours(1));
        cancellationRepository.save(delivery);

        assertThat(cancellationRepository.findExpiredPending(OffsetDateTime.now())).isEmpty();

        // Preuve positive que la méthode fonctionne bien quand la ligne est HANDOVER.
        CancellationEntity handover = new CancellationEntity();
        handover.setBidId(bid.getId());
        handover.setCancelledBy(bid.getSenderId());
        handover.setReason("SENDER_NO_SHOW");
        handover.setScope(CancellationScope.HANDOVER);
        handover.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        handover.setContestationDeadline(OffsetDateTime.now().minusHours(1));
        cancellationRepository.save(handover);

        assertThat(cancellationRepository.findExpiredPending(OffsetDateTime.now())).hasSize(1);
    }

    private BidEntity persistBid() {
        // createdAt/updatedAt sont auto-renseignés par BaseEntity#onCreate
        // (@PrePersist) — BaseEntity n'expose pas de setter public pour ces
        // champs, contrairement à ce que suggérait le brief.
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(new java.math.BigDecimal("5.0"));
        bid.setStatus(BidStatus.IN_TRANSIT);
        return bidRepository.save(bid);
    }
}
