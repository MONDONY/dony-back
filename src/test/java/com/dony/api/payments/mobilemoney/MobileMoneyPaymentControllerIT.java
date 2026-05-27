package com.dony.api.payments.mobilemoney;

import com.dony.api.auth.UserEntity;
import com.dony.api.common.DonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MobileMoneyPaymentControllerIT {

    @Autowired private MockMvc mockMvc;
    @MockBean  private MobileMoneyPaymentService paymentService;

    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID BID_ID    = UUID.randomUUID();

    private UserEntity senderEntity;

    @BeforeEach
    void setUp() throws Exception {
        senderEntity = new UserEntity();
        var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(senderEntity, SENDER_ID);
    }

    private UsernamePasswordAuthenticationToken senderAuth() {
        return new UsernamePasswordAuthenticationToken(
                senderEntity, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private UsernamePasswordAuthenticationToken travelerAuth() {
        UserEntity traveler = new UserEntity();
        try {
            var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(traveler, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        return new UsernamePasswordAuthenticationToken(
                traveler, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    // ── initiate ────────────────────────────────────────────────────────────

    @Test
    void initiate_validRequest_returns201WithPaymentLink() throws Exception {
        MobileMoneyPaymentEntity entity = pendingEntity();
        when(paymentService.initiate(any(), any())).thenReturn(entity);

        mockMvc.perform(post("/bids/{bidId}/mobile-money/initiate", BID_ID)
                        .with(authentication(senderAuth())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentLink").value("https://wave.test/pay?ref=wave_ref_1"));
    }

    @Test
    void initiate_unauthenticated_returns403() throws Exception {
        mockMvc.perform(post("/bids/{bidId}/mobile-money/initiate", BID_ID))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void initiate_travelerRole_returns403() throws Exception {
        // Only SENDER role is allowed to call initiate
        mockMvc.perform(post("/bids/{bidId}/mobile-money/initiate", BID_ID)
                        .with(authentication(travelerAuth())))
                .andExpect(status().isForbidden());
    }

    // ── getStatus ────────────────────────────────────────────────────────────

    @Test
    void getStatus_existingPayment_returns200() throws Exception {
        MobileMoneyPaymentEntity entity = pendingEntity();
        when(paymentService.getStatus(any(), any())).thenReturn(Optional.of(entity));

        mockMvc.perform(get("/bids/{bidId}/mobile-money/status", BID_ID)
                        .with(authentication(senderAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getStatus_noPaymentFound_returns404() throws Exception {
        when(paymentService.getStatus(any(), any())).thenThrow(
                new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "mm-payment-not-found", "Not Found",
                        "Aucun paiement Mobile Money trouvé pour ce bid"));

        mockMvc.perform(get("/bids/{bidId}/mobile-money/status", BID_ID)
                        .with(authentication(senderAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStatus_noPaymentReturnsEmpty_serviceReturnsEmpty_throws404() throws Exception {
        // Controller throws 404 when service returns empty Optional
        when(paymentService.getStatus(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/bids/{bidId}/mobile-money/status", BID_ID)
                        .with(authentication(senderAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStatus_unauthenticated_returns403() throws Exception {
        mockMvc.perform(get("/bids/{bidId}/mobile-money/status", BID_ID))
                .andExpect(status().is4xxClientError());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MobileMoneyPaymentEntity pendingEntity() {
        MobileMoneyPaymentEntity e = new MobileMoneyPaymentEntity();
        try {
            var idField = com.dony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, UUID.randomUUID());
        } catch (Exception ex) { throw new RuntimeException(ex); }
        e.setBidId(BID_ID);
        e.setStatus("PENDING");
        e.setPaymentLink("https://wave.test/pay?ref=wave_ref_1");
        e.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30));
        e.setAmount(new BigDecimal("50.00"));
        e.setCurrency("XOF");
        e.setFailureReason(null);
        return e;
    }
}
