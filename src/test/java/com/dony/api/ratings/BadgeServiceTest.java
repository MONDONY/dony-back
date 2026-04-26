package com.dony.api.ratings;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.auth.UserStatus;
import com.dony.api.common.AuditService;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.matching.BidStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BadgeService — tests unitaires")
class BadgeServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private BidRepository bidRepository;
    @Mock private RatingRepository ratingRepository;
    @Mock private AuditService auditService;

    @InjectMocks private BadgeService badgeService;

    private static final UUID TRAVELER_ID = UUID.randomUUID();

    private UserEntity traveler;

    @BeforeEach
    void setUp() throws Exception {
        traveler = new UserEntity();
        setId(traveler, TRAVELER_ID);
        setField(traveler, "status", UserStatus.ACTIVE);
        setField(traveler, "kiloPro", false);
    }

    @Nested
    @DisplayName("checkAndGrantKiloPro()")
    class CheckAndGrantTests {

        @Test
        @DisplayName("critères remplis → kiloPro accordé")
        void checkAndGrantKiloPro_allCriteriaMet_grantsBadge() {
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findCompletedBidsByTravelerId(TRAVELER_ID))
                    .thenReturn(buildDeliveries(5, true, false));
            when(ratingRepository.findRecentIncludedRatings(TRAVELER_ID))
                    .thenReturn(buildRatings(5, 4));
            when(userRepository.save(any())).thenReturn(traveler);

            badgeService.checkAndGrantKiloPro(TRAVELER_ID);

            assertThat(traveler.isKiloPro()).isTrue();
            assertThat(traveler.getKiloProGrantedAt()).isNotNull();
            verify(userRepository).save(traveler);
        }

        @Test
        @DisplayName("< 5 livraisons → badge non accordé")
        void checkAndGrantKiloPro_notEnoughDeliveries_notGranted() {
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findCompletedBidsByTravelerId(TRAVELER_ID))
                    .thenReturn(buildDeliveries(3, true, false));

            badgeService.checkAndGrantKiloPro(TRAVELER_ID);

            assertThat(traveler.isKiloPro()).isFalse();
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("même destinataire (farming) → badge refusé + audit_log")
        void checkAndGrantKiloPro_sameRecipient_farmingDetected() {
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findCompletedBidsByTravelerId(TRAVELER_ID))
                    .thenReturn(buildDeliveries(5, false, false)); // all same recipient

            badgeService.checkAndGrantKiloPro(TRAVELER_ID);

            assertThat(traveler.isKiloPro()).isFalse();
            verify(auditService).log(eq("USER"), eq(TRAVELER_ID), eq("KILO_PRO_FARMING_DETECTED"),
                    eq(TRAVELER_ID), any());
        }

        @Test
        @DisplayName("livraisons trop rapprochées (< 7 jours) → farming détecté")
        void checkAndGrantKiloPro_deliveriesTooClose_farmingDetected() {
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findCompletedBidsByTravelerId(TRAVELER_ID))
                    .thenReturn(buildDeliveries(5, true, true)); // 5 distinct recipients, 1 day apart

            badgeService.checkAndGrantKiloPro(TRAVELER_ID);

            assertThat(traveler.isKiloPro()).isFalse();
            verify(auditService).log(eq("USER"), eq(TRAVELER_ID), eq("KILO_PRO_FARMING_DETECTED"),
                    eq(TRAVELER_ID), any());
        }

        @Test
        @DisplayName("note moyenne < 4.0 → badge non accordé")
        void checkAndGrantKiloPro_lowAverage_notGranted() {
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));
            when(bidRepository.findCompletedBidsByTravelerId(TRAVELER_ID))
                    .thenReturn(buildDeliveries(5, true, false));
            when(ratingRepository.findRecentIncludedRatings(TRAVELER_ID))
                    .thenReturn(buildRatings(5, 3)); // average = 3

            badgeService.checkAndGrantKiloPro(TRAVELER_ID);

            assertThat(traveler.isKiloPro()).isFalse();
        }

        @Test
        @DisplayName("voyageur déjà kiloPro → aucune action")
        void checkAndGrantKiloPro_alreadyKiloPro_noOp() throws Exception {
            setField(traveler, "kiloPro", true);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            badgeService.checkAndGrantKiloPro(TRAVELER_ID);

            verify(bidRepository, never()).findCompletedBidsByTravelerId(any());
        }

        @Test
        @DisplayName("voyageur suspendu → aucune action")
        void checkAndGrantKiloPro_suspendedUser_noOp() throws Exception {
            setField(traveler, "status", UserStatus.SUSPENDED);
            when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(traveler));

            badgeService.checkAndGrantKiloPro(TRAVELER_ID);

            verify(bidRepository, never()).findCompletedBidsByTravelerId(any());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private List<BidEntity> buildDeliveries(int count, boolean distinctRecipients, boolean closeDates) {
        List<BidEntity> bids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BidEntity b = new BidEntity();
            try {
                setField(b, "status", BidStatus.COMPLETED);
                setField(b, "recipientPhone", distinctRecipients ? "+336" + String.format("%08d", i) : "+33612345678");
                int daysOffset = closeDates ? i : i * 10; // close = 1 day apart, spread = 10 days apart
                setField(b, "updatedAt", LocalDateTime.now().minusDays(count - i).minusDays(daysOffset));
            } catch (Exception ignored) {}
            bids.add(b);
        }
        return bids;
    }

    private List<RatingEntity> buildRatings(int count, int stars) {
        List<RatingEntity> ratings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RatingEntity r = new RatingEntity();
            r.setStars(stars);
            r.setRatedUserId(TRAVELER_ID);
            ratings.add(r);
        }
        return ratings;
    }

    private static void setId(Object obj, UUID id) throws Exception {
        Field f = obj.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(obj, id);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f;
        try {
            f = obj.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = obj.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(obj, value);
    }
}
