package com.dony.api.payments;

import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.TransportMode;
import com.dony.api.matching.dto.AnnouncementRevenueRow;
import com.dony.api.requests.entity.NegotiationThreadEntity;
import com.dony.api.requests.entity.NegotiationThreadStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Régression sur l'agrégation revenu voyageur : un paiement issu du flux
 * négociation / trajet dédié a {@code bidId = NULL} (keyé sur le thread). Les
 * requêtes ne doivent pas le laisser tomber à cause d'un INNER JOIN sur le bid.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PaymentRepositoryRevenueTest {

    @Autowired PaymentRepository paymentRepository;
    @Autowired TestEntityManager em;

    private static final LocalDateTime FROM = LocalDateTime.now().minusDays(1);
    private static final LocalDateTime TO = LocalDateTime.now().plusDays(1);

    private int piSeq = 0;

    private AnnouncementEntity newAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(LocalDate.of(2026, 8, 15));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        a.setDeliveryLat(new BigDecimal("12.533579"));
        a.setDeliveryLng(new BigDecimal("-7.948969"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.COMPLETED);
        return em.persistAndFlush(a);
    }

    private BidEntity newBid(UUID announcementId) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        return em.persistAndFlush(b);
    }

    private NegotiationThreadEntity newThread(UUID travelerId, UUID travelerAnnouncementId) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(UUID.randomUUID());
        t.setTravelerId(travelerId);
        t.setTravelerAnnouncementId(travelerAnnouncementId);
        t.setTravelerTravelDate(LocalDate.of(2026, 8, 15));
        t.setTravelerAvailableKg(new BigDecimal("15.00"));
        t.setStatus(NegotiationThreadStatus.ACCEPTED);
        t.setCurrentPriceEur(new BigDecimal("200.00"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        return em.persistAndFlush(t);
    }

    private PaymentEntity newPayment(
            UUID bidId, UUID threadId, String amount, String commission, PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setStripePaymentIntentId("pi_test_" + (piSeq++));
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(commission));
        p.setStatus(status);
        return em.persistAndFlush(p);
    }

    @Test
    void threadPayment_isCountedInPeriodRevenue() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID thread = newThread(traveler, ann).getId();
        // bidId NULL : paiement keyé sur le thread (flux négociation).
        newPayment(null, thread, "200.00", "24.00", PaymentStatus.RELEASED);

        BigDecimal revenue = paymentRepository.sumCapturedRevenueForTraveler(
                traveler, PaymentStatus.RELEASED, FROM, TO);

        // net = amount - commission = 176. Avant fix : 0 (ligne jetée par l'INNER JOIN).
        assertThat(revenue).isEqualByComparingTo("176.00");
    }

    @Test
    void bidPayment_stillCountedInPeriodRevenue() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID bid = newBid(ann).getId();
        newPayment(bid, null, "100.00", "12.00", PaymentStatus.RELEASED);

        BigDecimal revenue = paymentRepository.sumCapturedRevenueForTraveler(
                traveler, PaymentStatus.RELEASED, FROM, TO);

        assertThat(revenue).isEqualByComparingTo("88.00");
    }

    @Test
    void bidAndThreadPayments_areSummedTogether() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID bid = newBid(ann).getId();
        UUID thread = newThread(traveler, ann).getId();
        newPayment(bid, null, "100.00", "12.00", PaymentStatus.RELEASED);
        newPayment(null, thread, "200.00", "24.00", PaymentStatus.RELEASED);

        BigDecimal revenue = paymentRepository.sumCapturedRevenueForTraveler(
                traveler, PaymentStatus.RELEASED, FROM, TO);

        assertThat(revenue).isEqualByComparingTo("264.00");
    }

    @Test
    void otherTravelerThreadPayment_isExcluded() {
        UUID traveler = UUID.randomUUID();
        UUID otherTraveler = UUID.randomUUID();
        UUID otherAnn = newAnnouncement(otherTraveler).getId();
        UUID otherThread = newThread(otherTraveler, otherAnn).getId();
        newPayment(null, otherThread, "200.00", "24.00", PaymentStatus.RELEASED);

        BigDecimal revenue = paymentRepository.sumCapturedRevenueForTraveler(
                traveler, PaymentStatus.RELEASED, FROM, TO);

        assertThat(revenue).isEqualByComparingTo("0");
    }

    @Test
    void nonReleasedThreadPayment_isExcluded() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID thread = newThread(traveler, ann).getId();
        newPayment(null, thread, "200.00", "24.00", PaymentStatus.ESCROW);

        BigDecimal revenue = paymentRepository.sumCapturedRevenueForTraveler(
                traveler, PaymentStatus.RELEASED, FROM, TO);

        assertThat(revenue).isEqualByComparingTo("0");
    }

    @Test
    void threadPayment_isCountedInTotalRevenue() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID thread = newThread(traveler, ann).getId();
        newPayment(null, thread, "200.00", "24.00", PaymentStatus.RELEASED);

        BigDecimal total = paymentRepository.sumTotalCapturedRevenueForTraveler(
                traveler, PaymentStatus.RELEASED);

        assertThat(total).isEqualByComparingTo("176.00");
    }

    @Test
    void threadPayment_isAttributedToTravelerAnnouncement() {
        UUID traveler = UUID.randomUUID();
        UUID ann = newAnnouncement(traveler).getId();
        UUID thread = newThread(traveler, ann).getId();
        newPayment(null, thread, "200.00", "24.00", PaymentStatus.RELEASED);

        List<AnnouncementRevenueRow> rows = paymentRepository.findReleasedRevenueByAnnouncement(
                traveler, PaymentStatus.RELEASED, FROM, TO);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).announcementId()).isEqualTo(ann);
        assertThat(rows.get(0).gross()).isEqualByComparingTo("200.00");
        assertThat(rows.get(0).commission()).isEqualByComparingTo("24.00");
    }
}
