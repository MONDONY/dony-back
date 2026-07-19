package com.dony.api.payments;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 11 (part 1) — {@code markCapturedIfEscrow} is a bulk {@code @Modifying} JPQL UPDATE, so a
 * pure Mockito unit test can't prove the SQL actually sets all 5 columns atomically. This uses a
 * real (H2) persistence context, following the same {@code @DataJpaTest} pattern as
 * {@code StripeEventInboxRepositoryTest}.
 *
 * <p>Bulk UPDATEs bypass the first-level cache, so every assertion goes through
 * {@link TestEntityManager#clear()} + a fresh {@code findById} to read back what actually landed
 * in the database — never the stale in-memory entity used to trigger the call.
 */
@DataJpaTest
@ActiveProfiles("test")
class PaymentRepositoryMarkCapturedIfEscrowTest {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private TestEntityManager em;

    private PaymentEntity persistEscrowPayment() {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        p.setAmount(new BigDecimal("30.00"));
        p.setCommissionAmount(new BigDecimal("3.60"));
        p.setStatus(PaymentStatus.ESCROW);
        em.persistAndFlush(p);
        return p;
    }

    @Test
    void setsCapturedAtAndAllFourSettlementColumnsAtomically() {
        PaymentEntity payment = persistEscrowPayment();
        Instant now = Instant.now();

        int updated = paymentRepository.markCapturedIfEscrow(payment.getId(), now,
                "EUR", 3000L, BigDecimal.ONE, "NONE");

        assertThat(updated).isEqualTo(1);

        em.clear(); // bulk UPDATE bypasses the persistence context — force a real DB read
        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();

        assertThat(reloaded.getCapturedAt()).isNotNull();
        assertThat(reloaded.getSettlementCurrency()).isEqualTo("EUR");
        assertThat(reloaded.getSettlementAmountMinor()).isEqualTo(3000L);
        assertThat(reloaded.getSettlementFxRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(reloaded.getSettlementRateSource()).isEqualTo("NONE");
    }

    @Test
    void secondCallLosesTheCasRaceAndLeavesSettlementUntouched() {
        PaymentEntity payment = persistEscrowPayment();

        int firstCall = paymentRepository.markCapturedIfEscrow(payment.getId(), Instant.now(),
                "EUR", 3000L, BigDecimal.ONE, "NONE");
        assertThat(firstCall).isEqualTo(1);

        // A concurrent/duplicate call must not overwrite the already-set settlement columns —
        // this is the exact double-capture protection CLAUDE.md rule #19 exists for.
        int secondCall = paymentRepository.markCapturedIfEscrow(payment.getId(), Instant.now(),
                "XOF", 999_999L, new BigDecimal("655.957"), "PEG");
        assertThat(secondCall).isEqualTo(0);

        em.clear();
        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();

        assertThat(reloaded.getSettlementCurrency()).isEqualTo("EUR");
        assertThat(reloaded.getSettlementAmountMinor()).isEqualTo(3000L);
        assertThat(reloaded.getSettlementFxRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(reloaded.getSettlementRateSource()).isEqualTo("NONE");
    }

    /**
     * Task 11 fix-forward — reproduces the exact stale-entity-save bug from
     * {@code NegotiationCaptureListener}: after {@code markCapturedIfEscrow}'s bulk UPDATE
     * commits the 5 columns atomically, the SAME in-memory {@code PaymentEntity} reference
     * (fetched BEFORE the bulk UPDATE, so still holding stale nulls for {@code capturedAt}
     * and the 4 settlement columns) has only {@code stripeChargeId} set on it and is saved.
     *
     * <p>Without {@code @DynamicUpdate} on {@code PaymentEntity}, Hibernate emits a
     * full-column UPDATE that pushes those stale in-memory nulls back over the DB row,
     * silently wiping out what the bulk UPDATE just set. With {@code @DynamicUpdate},
     * Hibernate's dirty-checking excludes the untouched columns from the generated SQL, so
     * only {@code stripe_charge_id} is written and the settlement columns survive.
     *
     * <p>This test is RED before {@code @DynamicUpdate} is added to {@code PaymentEntity} and
     * GREEN after.
     */
    @Test
    void staleEntitySaveAfterBulkCaptureUpdateDoesNotClobberSettlementColumns() {
        PaymentEntity payment = persistEscrowPayment(); // managed ref, capturedAt=null, settlement=null

        int updated = paymentRepository.markCapturedIfEscrow(payment.getId(), Instant.now(),
                "EUR", 3000L, BigDecimal.ONE, "NONE");
        assertThat(updated).isEqualTo(1);

        // Simulates NegotiationCaptureListener: same stale reference as before the bulk UPDATE,
        // only stripeChargeId is mutated, then saved — must NOT full-column-overwrite the rest.
        payment.setStripeChargeId("ch_test");
        paymentRepository.save(payment);
        em.flush();

        em.clear();
        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();

        assertThat(reloaded.getStripeChargeId()).isEqualTo("ch_test");
        assertThat(reloaded.getCapturedAt()).isNotNull();
        assertThat(reloaded.getSettlementCurrency()).isEqualTo("EUR");
        assertThat(reloaded.getSettlementAmountMinor()).isEqualTo(3000L);
        assertThat(reloaded.getSettlementFxRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(reloaded.getSettlementRateSource()).isEqualTo("NONE");
    }

    @Test
    void doesNotCaptureAndLeavesSettlementNullWhenNotInEscrow() {
        PaymentEntity payment = persistEscrowPayment();
        payment.setStatus(PaymentStatus.RELEASED);
        em.persistAndFlush(payment);

        int updated = paymentRepository.markCapturedIfEscrow(payment.getId(), Instant.now(),
                "EUR", 3000L, BigDecimal.ONE, "NONE");
        assertThat(updated).isEqualTo(0);

        em.clear();
        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();

        assertThat(reloaded.getCapturedAt()).isNull();
        assertThat(reloaded.getSettlementCurrency()).isNull();
        assertThat(reloaded.getSettlementAmountMinor()).isNull();
        assertThat(reloaded.getSettlementFxRate()).isNull();
        assertThat(reloaded.getSettlementRateSource()).isNull();
    }
}
