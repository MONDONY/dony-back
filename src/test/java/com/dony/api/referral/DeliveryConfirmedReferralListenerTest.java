package com.dony.api.referral;

import com.dony.api.common.AuditService;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryConfirmedReferralListener — unit tests")
class DeliveryConfirmedReferralListenerTest {

    @Mock private ReferralInvitationRepository referralInvitationRepository;
    @Mock private UserCreditRepository userCreditRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;

    private ReferralConfig config;
    private DeliveryConfirmedReferralListener listener;

    private static final UUID SENDER_ID   = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID BID_ID      = UUID.randomUUID();
    private static final UUID REFERRER_ID = UUID.randomUUID();
    private static final UUID INV_ID      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        config = new ReferralConfig();
        config.setRewardAmountCents(500);
        config.setCodeRegenerationCooldownDays(30);
        listener = new DeliveryConfirmedReferralListener(
                referralInvitationRepository, userCreditRepository,
                bidRepository, auditService, config);
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReferralInvitationEntity buildSignedUpInvitation() {
        ReferralInvitationEntity inv = new ReferralInvitationEntity();
        setId(inv, INV_ID);
        inv.setReferrerUserId(REFERRER_ID);
        inv.setRefereeUserId(SENDER_ID);
        inv.setStatus("SIGNED_UP");
        inv.setCodeUsed("TEST1234");
        return inv;
    }

    private DeliveryConfirmedEvent event() {
        return new DeliveryConfirmedEvent(BID_ID, SENDER_ID, TRAVELER_ID);
    }

    // ── 1. noInvitation_doesNothing ───────────────────────────────────────────

    @Test
    @DisplayName("noInvitation_doesNothing — no SIGNED_UP invitation → no reward")
    void noInvitation_doesNothing() {
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.empty());

        listener.onDeliveryConfirmed(event());

        verifyNoInteractions(bidRepository, userCreditRepository);
        verify(referralInvitationRepository, never()).save(any());
    }

    // ── 2. firstDelivery_rewards ──────────────────────────────────────────────

    @Test
    @DisplayName("firstDelivery_rewards — first COMPLETED bid triggers reward and credit")
    void firstDelivery_rewards() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(1L);
        when(referralInvitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userCreditRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        listener.onDeliveryConfirmed(event());

        // Invitation must be updated to REWARDED
        ArgumentCaptor<ReferralInvitationEntity> invCaptor =
                ArgumentCaptor.forClass(ReferralInvitationEntity.class);
        verify(referralInvitationRepository).save(invCaptor.capture());
        ReferralInvitationEntity saved = invCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("REWARDED");
        assertThat(saved.getCreditAmountCents()).isEqualTo(500);
        assertThat(saved.getRewardedAt()).isNotNull();

        // Credit entry must be created for the referrer
        ArgumentCaptor<UserCreditEntity> creditCaptor =
                ArgumentCaptor.forClass(UserCreditEntity.class);
        verify(userCreditRepository).save(creditCaptor.capture());
        UserCreditEntity credit = creditCaptor.getValue();
        assertThat(credit.getUserId()).isEqualTo(REFERRER_ID);
        assertThat(credit.getAmountCents()).isEqualTo(500);
        assertThat(credit.getSource()).isEqualTo("REFERRAL_REWARD");
        assertThat(credit.getReferenceId()).isEqualTo(INV_ID);
    }

    // ── 3. secondDelivery_doesNotReward ───────────────────────────────────────

    @Test
    @DisplayName("secondDelivery_doesNotReward — 2+ completed bids → no reward")
    void secondDelivery_doesNotReward() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(2L);

        listener.onDeliveryConfirmed(event());

        verify(referralInvitationRepository, never()).save(any());
        verifyNoInteractions(userCreditRepository);
    }

    // ── 4. rewardCreatesUserCredit ────────────────────────────────────────────

    @Test
    @DisplayName("rewardCreatesUserCredit — credit has correct referenceId = invitationId")
    void rewardCreatesUserCredit() {
        ReferralInvitationEntity inv = buildSignedUpInvitation();
        when(referralInvitationRepository.findByRefereeUserIdAndStatus(SENDER_ID, "SIGNED_UP"))
                .thenReturn(Optional.of(inv));
        when(bidRepository.countByStatusAndSenderId(BidStatus.COMPLETED, SENDER_ID)).thenReturn(1L);
        when(referralInvitationRepository.save(any())).thenReturn(inv);
        when(userCreditRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        listener.onDeliveryConfirmed(event());

        ArgumentCaptor<UserCreditEntity> cap = ArgumentCaptor.forClass(UserCreditEntity.class);
        verify(userCreditRepository).save(cap.capture());
        assertThat(cap.getValue().getReferenceId()).isEqualTo(INV_ID);
    }
}
