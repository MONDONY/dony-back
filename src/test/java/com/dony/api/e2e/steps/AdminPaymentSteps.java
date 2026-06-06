package com.dony.api.e2e.steps;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.matching.TransportMode;
import com.dony.api.payments.PaymentEntity;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import io.cucumber.java.fr.Etantdonné;
import io.cucumber.java.fr.Quand;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Steps for the admin escrow force-release endpoint
 * ({@code POST /admin/payments/{id}/force-release}).
 */
public class AdminPaymentSteps extends AbstractSteps {

    @Autowired private BidRepository bidRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AnnouncementRepository announcementRepository;

    // ── Given ─────────────────────────────────────────────────────────────────

    @Etantdonné("l'administrateur {string} est authentifié")
    public void givenAdminAuthenticated(String uid) {
        ctx.setCurrentUser(uid, "ROLE_ADMIN");
    }

    /**
     * Seeds a CANCELLED bid carrying an ESCROW payment — the state that arises when a paid
     * trip is later cancelled. Seeded directly through the repositories (full valid FK chain:
     * traveler + sender users → announcement → bid → payment) so the scenario does not depend
     * on the negotiation/Stripe flow, which cannot create a real escrow payment in E2E.
     */
    @Etantdonné("un colis annulé avec un paiement en escrow de {decimal} € sauvegardé sous {string}")
    public void givenCancelledColisWithEscrow(Double amount, String alias) {
        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("seed-traveler-" + alias);
        UserEntity savedTraveler = userRepository.save(traveler);

        UserEntity sender = new UserEntity();
        sender.setFirebaseUid("seed-sender-" + alias);
        UserEntity savedSender = userRepository.save(sender);

        AnnouncementEntity ann = new AnnouncementEntity();
        ann.setTravelerId(savedTraveler.getId());
        ann.setDepartureCity("Paris");
        ann.setArrivalCity("Dakar");
        ann.setDepartureDate(LocalDate.now().plusDays(7));
        ann.setTransportMode(TransportMode.PLANE);
        ann.setPickupAddressLabel("Gare du Nord, Paris");
        ann.setPickupLat(new BigDecimal("48.880"));
        ann.setPickupLng(new BigDecimal("2.355"));
        ann.setDeliveryAddressLabel("Plateau, Dakar");
        ann.setDeliveryLat(new BigDecimal("14.693"));
        ann.setDeliveryLng(new BigDecimal("-17.447"));
        ann.setAvailableKg(new BigDecimal("20.00"));
        ann.setTotalKg(new BigDecimal("20.00"));
        ann.setPricePerKg(new BigDecimal("5.00"));
        AnnouncementEntity savedAnn = announcementRepository.save(ann);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(savedAnn.getId());
        bid.setSenderId(savedSender.getId());
        bid.setStatus(BidStatus.CANCELLED);
        BidEntity savedBid = bidRepository.save(bid);

        PaymentEntity payment = new PaymentEntity();
        payment.setBidId(savedBid.getId());
        payment.setStripePaymentIntentId("pi_e2e_" + savedBid.getId());
        payment.setAmount(BigDecimal.valueOf(amount));
        payment.setCommissionAmount(BigDecimal.valueOf(amount).multiply(new BigDecimal("0.12")));
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setLegacyDestinationCharge(false);
        PaymentEntity savedPayment = paymentRepository.save(payment);

        ctx.saveId(alias + "-payment", savedPayment.getId());
    }

    // ── When ──────────────────────────────────────────────────────────────────

    @Quand("je force la libération du paiement du colis {string}")
    public void whenForceReleaseByColis(String alias) {
        store(asCurrentUser().post("/admin/payments/{id}/force-release",
                ctx.getId(alias + "-payment")));
    }

    @Quand("je force la libération du paiement {string}")
    public void whenForceReleaseById(String paymentId) {
        store(asCurrentUser().post("/admin/payments/{id}/force-release", paymentId));
    }

    @Quand("je rembourse le paiement {string}")
    public void whenRefundById(String paymentId) {
        store(asCurrentUser().post("/admin/payments/{id}/refund", paymentId));
    }
}
