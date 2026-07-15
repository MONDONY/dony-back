package com.dony.api.disputes;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preuve DB-level (pas de mock) que le filtre implicite type='SENDER_NO_SHOW_CONTESTED'
 * de {@link DisputeRepository#findByBidId} exclut bien les litiges d'un autre type sur
 * le même bid, et que la contrainte d'unicité (bid_id, type) laisse cohabiter les deux.
 *
 * Fait suite au finding du reviewer sur la task A1 sœur (cancellation) : une suite verte
 * basée uniquement sur des mocks Mockito prouve la validité syntaxique du @Query mais pas
 * l'exclusion logique réelle au niveau SQL.
 */
@DataJpaTest
@ActiveProfiles("test")
class DisputeRepositoryScopeTest {

    @Autowired DisputeRepository disputeRepository;

    @Test
    void findByBidId_ignoresOtherTypeDisputeOnSameBid() {
        UUID bidId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        // Litige d'arrivée sur le même bid : matcherait findByBidId() si le filtre
        // implicite type='SENDER_NO_SHOW_CONTESTED' du @Query n'était pas appliqué.
        DisputeEntity deliveryDispute = new DisputeEntity();
        deliveryDispute.setBidId(bidId);
        deliveryDispute.setSenderId(senderId);
        deliveryDispute.setTravelerId(travelerId);
        deliveryDispute.setType("RECIPIENT_NO_SHOW_CONTESTED");
        deliveryDispute.setStatus("OPEN");
        deliveryDispute.setRefundFrozen(true);
        disputeRepository.save(deliveryDispute);

        assertThat(disputeRepository.findByBidId(bidId)).isEmpty();

        // Preuve positive : la méthode fonctionne bien quand un litige de départ existe.
        DisputeEntity senderDispute = new DisputeEntity();
        senderDispute.setBidId(bidId);
        senderDispute.setSenderId(senderId);
        senderDispute.setTravelerId(travelerId);
        senderDispute.setType("SENDER_NO_SHOW_CONTESTED");
        senderDispute.setStatus("OPEN");
        senderDispute.setRefundFrozen(true);
        disputeRepository.save(senderDispute);

        assertThat(disputeRepository.findByBidId(bidId)).isPresent();
        assertThat(disputeRepository.findByBidId(bidId).get().getType())
                .isEqualTo("SENDER_NO_SHOW_CONTESTED");
    }

    @Test
    void twoDisputesOfDifferentTypesCanCoexistOnSameBid() {
        UUID bidId = UUID.randomUUID();

        DisputeEntity senderDispute = new DisputeEntity();
        senderDispute.setBidId(bidId);
        senderDispute.setSenderId(UUID.randomUUID());
        senderDispute.setTravelerId(UUID.randomUUID());
        senderDispute.setType("SENDER_NO_SHOW_CONTESTED");
        senderDispute.setStatus("OPEN");
        senderDispute.setRefundFrozen(true);
        disputeRepository.save(senderDispute);

        DisputeEntity deliveryDispute = new DisputeEntity();
        deliveryDispute.setBidId(bidId);
        deliveryDispute.setSenderId(UUID.randomUUID());
        deliveryDispute.setTravelerId(UUID.randomUUID());
        deliveryDispute.setType("RECIPIENT_NO_SHOW_CONTESTED");
        deliveryDispute.setStatus("OPEN");
        deliveryDispute.setRefundFrozen(true);

        // Ne doit pas lever de violation de contrainte d'unicité (bid_id, type) —
        // même bid_id, type différent.
        disputeRepository.save(deliveryDispute);
        disputeRepository.flush();

        assertThat(disputeRepository.findByBidIdAndType(bidId, "SENDER_NO_SHOW_CONTESTED")).isPresent();
        assertThat(disputeRepository.findByBidIdAndType(bidId, "RECIPIENT_NO_SHOW_CONTESTED")).isPresent();
    }
}
