package com.dony.api.ratings;

import com.dony.api.common.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FraudDetectionService — tests unitaires")
class FraudDetectionServiceTest {

    @Mock private RatingRepository ratingRepository;
    @Mock private AuditService auditService;

    @InjectMocks private FraudDetectionService fraudDetectionService;

    private static final UUID RATER_ID = UUID.randomUUID();
    private static final UUID RATED_USER_ID = UUID.randomUUID();
    private static final UUID RATING_ID = UUID.randomUUID();

    @Nested
    @DisplayName("detectRatingFarming()")
    class DetectTests {

        @Test
        @DisplayName("> 3 notations du même expéditeur en 30j → toutes exclues + audit")
        void detect_tooManyFromSamePair_excludesAllAndLogs() {
            RatingEntity rating = buildRating(RATER_ID, RATED_USER_ID, 5, null);
            when(ratingRepository.findById(RATING_ID)).thenReturn(Optional.of(rating));
            when(ratingRepository.findByRaterIdAndRatedUserIdSince(eq(RATER_ID), eq(RATED_USER_ID), any()))
                    .thenReturn(buildRatings(4, true, null));

            fraudDetectionService.detectRatingFarming(RATING_ID);

            verify(ratingRepository).saveAll(anyList());
            verify(auditService).log(eq("RATING"), any(), eq("FRAUD_ALERT_RATING_FARMING"), isNull(), any());
        }

        @Test
        @DisplayName("toutes les notations sont 5★ sans commentaire → exclues + audit")
        void detect_allPerfectNoComment_excludesAndLogs() {
            RatingEntity rating = buildRating(RATER_ID, RATED_USER_ID, 5, null);
            when(ratingRepository.findById(RATING_ID)).thenReturn(Optional.of(rating));
            when(ratingRepository.findByRaterIdAndRatedUserIdSince(eq(RATER_ID), eq(RATED_USER_ID), any()))
                    .thenReturn(buildRatings(2, true, null)); // 2 = <= MAX, but all perfect no comment

            fraudDetectionService.detectRatingFarming(RATING_ID);

            verify(ratingRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("notations normales (variées) → aucune action")
        void detect_normalRatings_noAction() {
            RatingEntity rating = buildRating(RATER_ID, RATED_USER_ID, 4, "Bon voyageur");
            when(ratingRepository.findById(RATING_ID)).thenReturn(Optional.of(rating));
            when(ratingRepository.findByRaterIdAndRatedUserIdSince(eq(RATER_ID), eq(RATED_USER_ID), any()))
                    .thenReturn(List.of(rating)); // seule notation avec commentaire

            fraudDetectionService.detectRatingFarming(RATING_ID);

            verify(ratingRepository, never()).saveAll(any());
            verify(auditService, never()).log(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("notation par destinataire (raterId null) → pas de vérification")
        void detect_recipientRating_skipped() {
            RatingEntity rating = buildRating(null, RATED_USER_ID, 5, null);
            when(ratingRepository.findById(RATING_ID)).thenReturn(Optional.of(rating));

            fraudDetectionService.detectRatingFarming(RATING_ID);

            verify(ratingRepository, never()).findByRaterIdAndRatedUserIdSince(any(), any(), any());
        }

        @Test
        @DisplayName("ratingId inexistant → aucune action")
        void detect_unknownRating_noOp() {
            when(ratingRepository.findById(RATING_ID)).thenReturn(Optional.empty());

            fraudDetectionService.detectRatingFarming(RATING_ID);

            verify(ratingRepository, never()).saveAll(any());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private RatingEntity buildRating(UUID raterId, UUID ratedUserId, int stars, String comment) {
        RatingEntity r = new RatingEntity();
        r.setRaterId(raterId);
        r.setRatedUserId(ratedUserId);
        r.setStars(stars);
        r.setComment(comment);
        return r;
    }

    private List<RatingEntity> buildRatings(int count, boolean allPerfect, String comment) {
        List<RatingEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RatingEntity r = buildRating(RATER_ID, RATED_USER_ID,
                    allPerfect ? 5 : (i % 2 == 0 ? 4 : 5),
                    comment);
            list.add(r);
        }
        return list;
    }
}
