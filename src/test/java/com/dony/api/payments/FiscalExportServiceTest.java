package com.dony.api.payments;

import com.dony.api.auth.UserEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FiscalExportServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;

    @InjectMocks private FiscalExportService service;

    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private UserEntity traveler;

    @BeforeEach
    void setup() throws Exception {
        traveler = new UserEntity();
        setField(traveler, "id", TRAVELER_ID);
        traveler.setFirstName("Kofi");
        traveler.setLastName("Mensah");
        setField(traveler, "country", "FR");
    }

    @Nested
    @DisplayName("generateCsv — summary")
    class SummaryCsv {

        @Test
        void containsHeaderAndTotals() {
            PaymentEntity p = buildPayment(BigDecimal.valueOf(100), BigDecimal.valueOf(12));
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of(p));

            byte[] csv = service.generateCsv(traveler, 2025, "summary");
            String content = new String(csv);

            assertThat(content).contains("Année").contains("Revenu brut").contains("Revenu net");
            assertThat(content).contains("2025");
            assertThat(content).contains("100.00");
            assertThat(content).contains("88.00"); // net = 100 - 12
        }
    }

    @Nested
    @DisplayName("generateCsv — transactions")
    class TransactionsCsv {

        @Test
        void containsTransactionRow() {
            PaymentEntity p = buildPayment(BigDecimal.valueOf(50), BigDecimal.valueOf(6));
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of(p));
            // bidId is null → resolveCorridorForPayment returns "—" without calling bidRepository

            byte[] csv = service.generateCsv(traveler, 2025, "transactions");
            String content = new String(csv);

            assertThat(content).contains("Date").contains("Trajet").contains("50.00").contains("44.00");
        }

        @Test
        void returnsJustHeaderOnNoPayments() {
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of());

            byte[] csv = service.generateCsv(traveler, 2025, "transactions");
            String content = new String(csv);

            assertThat(content).contains("Date,Trajet");
            assertThat(content.lines().count()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("generateCsv — dac7")
    class Dac7Csv {

        @Test
        void containsDac7Fields() {
            PaymentEntity p = buildPayment(BigDecimal.valueOf(200), BigDecimal.valueOf(24));
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of(p));

            byte[] csv = service.generateCsv(traveler, 2025, "dac7");
            String content = new String(csv);

            assertThat(content).contains("DAC7");
            assertThat(content).contains("Kofi");
            assertThat(content).contains("200.00");
            assertThat(content).contains("176.00");
        }
    }

    @Nested
    @DisplayName("generateHtml — pdf")
    class GenerateHtml {

        @Test
        void returnsSummaryHtml() {
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of());

            byte[] html = service.generateHtml(traveler, 2025, "summary");
            String content = new String(html);

            assertThat(content).contains("<!DOCTYPE html>");
            assertThat(content).contains("2025");
            assertThat(content).contains("Kofi Mensah");
        }

        @Test
        void returnsTransactionsHtml() {
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of());

            byte[] html = service.generateHtml(traveler, 2025, "transactions");
            String content = new String(html);

            assertThat(content).contains("Transactions 2025");
        }

        @Test
        void returnsDac7Html() {
            when(paymentRepository.findReleasedByTravelerAndYear(eq(TRAVELER_ID), any(), any()))
                    .thenReturn(List.of());

            byte[] html = service.generateHtml(traveler, 2025, "dac7");
            String content = new String(html);

            assertThat(content).contains("DAC7");
            assertThat(content).contains("Kofi Mensah");
        }
    }

    // ---- Helpers ----

    private PaymentEntity buildPayment(BigDecimal amount, BigDecimal commission) {
        PaymentEntity p = new PaymentEntity();
        try {
            setField(p, "id", UUID.randomUUID());
            setField(p, "amount", amount);
            setField(p, "commissionAmount", commission);
            setField(p, "status", PaymentStatus.RELEASED);
            setField(p, "createdAt", LocalDateTime.now());
        } catch (Exception ignored) {}
        return p;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
