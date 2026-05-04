package com.dony.api.auth;

import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SenderStatsListenerIT {

    @Autowired UserRepository userRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate transactionTemplate;

    private UserEntity persistSender(int totalShipments) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setPhoneNumber("+33" + System.nanoTime());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        u.setRoles(roles);
        u.setTotalShipments(totalShipments);
        return userRepository.save(u);
    }

    private BidEntity persistCompletedBid(UUID senderId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setSenderId(senderId);
        bid.setWeightKg(new BigDecimal("2.50"));
        bid.setDeclaredValueEur(new BigDecimal("100.00"));
        bid.setStatus(BidStatus.COMPLETED);
        return bidRepository.save(bid);
    }

    @Test
    void increments_total_shipments_after_commit() {
        UserEntity sender = persistSender(3);
        BidEntity bid = persistCompletedBid(sender.getId());

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(new DeliveryConfirmedEvent(
                        bid.getId(), sender.getId(), UUID.randomUUID())));

        UserEntity reloaded = userRepository.findById(sender.getId()).orElseThrow();
        BidEntity reloadedBid = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(reloaded.getTotalShipments()).isEqualTo(4);
        assertThat(reloadedBid.isShipmentCounted()).isTrue();
    }

    @Test
    void three_bids_completed_for_same_sender_each_increment() {
        UserEntity sender = persistSender(0);
        BidEntity b1 = persistCompletedBid(sender.getId());
        BidEntity b2 = persistCompletedBid(sender.getId());
        BidEntity b3 = persistCompletedBid(sender.getId());

        for (BidEntity bid : new BidEntity[]{b1, b2, b3}) {
            transactionTemplate.executeWithoutResult(status ->
                    eventPublisher.publishEvent(new DeliveryConfirmedEvent(
                            bid.getId(), sender.getId(), UUID.randomUUID())));
        }

        UserEntity reloaded = userRepository.findById(sender.getId()).orElseThrow();
        assertThat(reloaded.getTotalShipments()).isEqualTo(3);
    }

    @Test
    void replay_of_event_does_not_double_count() {
        UserEntity sender = persistSender(0);
        BidEntity bid = persistCompletedBid(sender.getId());

        DeliveryConfirmedEvent event = new DeliveryConfirmedEvent(
                bid.getId(), sender.getId(), UUID.randomUUID());

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        UserEntity reloaded = userRepository.findById(sender.getId()).orElseThrow();
        assertThat(reloaded.getTotalShipments()).isEqualTo(1);
    }

    @Test
    void does_not_increment_when_parent_transaction_rolls_back() {
        UserEntity sender = persistSender(7);
        BidEntity bid = persistCompletedBid(sender.getId());

        try {
            transactionTemplate.executeWithoutResult(status -> {
                eventPublisher.publishEvent(new DeliveryConfirmedEvent(
                        bid.getId(), sender.getId(), UUID.randomUUID()));
                status.setRollbackOnly();
            });
        } catch (Exception ignored) {
            // some Spring versions throw UnexpectedRollbackException
        }

        UserEntity reloaded = userRepository.findById(sender.getId()).orElseThrow();
        BidEntity reloadedBid = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(reloaded.getTotalShipments()).isEqualTo(7);
        assertThat(reloadedBid.isShipmentCounted()).isFalse();
    }
}
