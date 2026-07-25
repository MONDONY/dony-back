package com.dony.api.admin.export;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.disputes.DisputeEntity;
import com.dony.api.disputes.DisputeRepository;
import com.dony.api.payments.PaymentEntity;
import com.dony.api.payments.PaymentRepository;
import com.dony.api.payments.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminExportServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock DisputeRepository disputeRepository;
    @Mock com.dony.api.auth.FirebaseContactService firebaseContact;

    private AdminExportService service() {
        org.mockito.Mockito.lenient()
                .when(firebaseContact.getContacts(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new java.util.HashMap<>());
        return new AdminExportService(paymentRepository, userRepository, disputeRepository, firebaseContact);
    }

    private static String text(byte[] csv) {
        return new String(csv, StandardCharsets.UTF_8);
    }

    @Test
    void exportTransactions_writesHeaderAndRows() {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setAmount(new BigDecimal("100.00"));
        p.setCommissionAmount(new BigDecimal("12.00"));
        p.setStatus(PaymentStatus.RELEASED);
        p.setStripePaymentIntentId("pi_123");
        when(paymentRepository.findAllByCreatedAtBetweenOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(p));

        String csv = text(service().exportTransactions(null, null));

        assertThat(csv).contains("id,bidId,statut,montantEur");
        assertThat(csv).contains("RELEASED");
        assertThat(csv).contains("100.00");
        assertThat(csv).contains("pi_123");
        assertThat(csv).startsWith("\uFEFF");
    }

    @Test
    void exportTransactions_dateRange_isInclusiveOfToDay() {
        when(paymentRepository.findAllByCreatedAtBetweenOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());
        ArgumentCaptor<LocalDateTime> fromCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCap = ArgumentCaptor.forClass(LocalDateTime.class);

        service().exportTransactions(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        verify(paymentRepository).findAllByCreatedAtBetweenOrderByCreatedAtAsc(fromCap.capture(), toCap.capture());
        assertThat(fromCap.getValue()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        // Borne haute exclusive au lendemain → couvre toute la journée du 31.
        assertThat(toCap.getValue()).isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
    }

    @Test
    void exportUsers_escapesCommasAndQuotes() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-jean");
        u.setFirstName("Jean, dit \"Jeannot\"");
        u.setLastName("Dupont");
        when(userRepository.findAllByCreatedAtBetweenOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(u));

        String csv = text(service().exportUsers(null, null));

        assertThat(csv).contains("\"Jean, dit \"\"Jeannot\"\"\"");
    }

    @Test
    void exportDisputes_includesResolutionColumns() {
        DisputeEntity d = new DisputeEntity();
        d.setBidId(UUID.randomUUID());
        d.setType("DAMAGED");
        d.setStatus("RESOLVED");
        d.setResolutionType("RESOLVED_FOR_SENDER");
        d.setDeclaredValueEur(new BigDecimal("250.00"));
        when(disputeRepository.findAllByCreatedAtBetweenOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(d));

        String csv = text(service().exportDisputes(null, null));

        assertThat(csv).contains("RESOLVED_FOR_SENDER");
        assertThat(csv).contains("250.00");
    }

    @Test
    void exportPayouts_onlyGuaranteePaid_withAmount() {
        DisputeEntity d = new DisputeEntity();
        d.setBidId(UUID.randomUUID());
        d.setBeneficiaryUserId(UUID.randomUUID());
        d.setGuaranteeAmountCents(15000L);
        d.setResolutionNote("colis perdu");
        d.setResolvedAt(OffsetDateTime.now());
        when(disputeRepository.findAllByResolutionTypeAndResolvedAtBetweenOrderByResolvedAtAsc(
                eq("GUARANTEE_PAID"), any(), any()))
                .thenReturn(List.of(d));

        String csv = text(service().exportPayouts(null, null));

        assertThat(csv).contains("disputeId,bidId,beneficiaireUserId,montantCents");
        assertThat(csv).contains("15000");
        assertThat(csv).contains("colis perdu");
    }

    @Test
    void exportPayouts_nullAmount_writesEmptyCell() {
        DisputeEntity d = new DisputeEntity();
        d.setResolvedAt(OffsetDateTime.now());
        when(disputeRepository.findAllByResolutionTypeAndResolvedAtBetweenOrderByResolvedAtAsc(
                eq("GUARANTEE_PAID"), any(), any()))
                .thenReturn(List.of(d));

        String csv = text(service().exportPayouts(null, null));

        // ligne = disputeId vide,bidId vide,beneficiaire vide,montant vide,motif vide,date
        assertThat(csv.lines().count()).isEqualTo(2);
    }
}
