package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.auth.Role;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.dto.BidCheckoutRequest;
import com.dony.api.matching.dto.BidCheckoutResponse;
import com.dony.api.payments.PaymentService;
import com.dony.api.payments.dto.CreatePaymentRequest;
import com.dony.api.payments.dto.PaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidCheckoutServiceTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private PaymentService paymentService;
    @Mock private HttpServletRequest httpRequest;

    @InjectMocks private BidCheckoutService service;

    private UserEntity sender;
    private AnnouncementEntity announcement;
    private BidCheckoutRequest req;

    @BeforeEach
    void setUp() {
        sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", UUID.randomUUID());
        sender.setFirebaseUid("uid-sender");
        sender.setRoles(new HashSet<>());

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(UUID.randomUUID());
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setAvailableKg(new BigDecimal("10.00"));

        req = new BidCheckoutRequest(
            announcement.getId(),
            new BigDecimal("2.00"),
            new BigDecimal("150.00"),
            "test", "OTHER",
            "Recipient", "+221771234567", true);

        lenient().when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        lenient().when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        lenient().when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    private PaymentResponse stubPaymentResponse() {
        return new PaymentResponse(UUID.randomUUID(), UUID.randomUUID(), "secret_xyz",
                                   BigDecimal.TEN, BigDecimal.ONE, "PENDING", "pi_test123");
    }

    @Test
    void creates_bid_in_AWAITING_PAYMENT_and_delegates_to_payment_service() {
        ArgumentCaptor<BidEntity> savedBid = ArgumentCaptor.forClass(BidEntity.class);
        when(bidRepository.save(savedBid.capture())).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            if (b.getId() == null) ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        when(paymentService.createEscrow(any(CreatePaymentRequest.class), eq("uid-sender")))
            .thenReturn(stubPaymentResponse());

        BidCheckoutResponse resp = service.checkout("uid-sender", req, httpRequest);

        BidEntity bid = savedBid.getAllValues().get(0);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.AWAITING_PAYMENT);
        assertThat(bid.getAwaitingPaymentExpiresAt()).isNotNull();
        assertThat(resp.clientSecret()).isEqualTo("secret_xyz");
        verify(auditService, never()).log(eq("BID"), any(), eq("BID_CREATED"), any(), any());
    }

    @Test
    void rejects_inactive_announcement() {
        announcement.setStatus(AnnouncementStatus.FULL);
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void rejects_bidding_on_own_announcement() {
        announcement.setTravelerId(sender.getId());
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void rejects_weight_exceeding_capacity() {
        announcement.setAvailableKg(new BigDecimal("1.00"));
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void rejects_value_above_500_eur() {
        BidCheckoutRequest tooHigh = new BidCheckoutRequest(
            announcement.getId(), new BigDecimal("2"), new BigDecimal("501"),
            null, null, null, null, true);
        assertThatThrownBy(() -> service.checkout("uid-sender", tooHigh, httpRequest))
            .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void rejects_existing_in_progress_bid() {
        when(bidRepository.existsBySenderIdAndAnnouncementIdAndStatusIn(
                eq(sender.getId()), eq(announcement.getId()), any()))
            .thenReturn(true);
        assertThatThrownBy(() -> service.checkout("uid-sender", req, httpRequest))
            .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void auto_assigns_SENDER_role() {
        when(bidRepository.save(any())).thenAnswer(inv -> {
            BidEntity b = inv.getArgument(0);
            if (b.getId() == null) ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        when(paymentService.createEscrow(any(), anyString())).thenReturn(stubPaymentResponse());

        service.checkout("uid-sender", req, httpRequest);

        assertThat(sender.getRoles()).contains(Role.SENDER);
        verify(userRepository).save(sender);
    }
}
