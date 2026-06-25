package com.dony.api.admin;

import com.dony.api.admin.dto.AdminRatingResponse;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.ratings.RatingEntity;
import com.dony.api.ratings.RatingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminRatingsControllerTest {

    @Mock RatingRepository ratingRepo;
    @Mock UserRepository userRepo;
    @Mock AuditService auditService;

    private AdminRatingsController controller() {
        return new AdminRatingsController(ratingRepo, userRepo, auditService);
    }

    // ---- listRatings ----

    @Test
    void listRatings_noFilter_returnsPage() {
        Page<RatingEntity> page = new PageImpl<>(List.of());
        when(ratingRepo.findAdminFiltered(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);
        when(userRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<Page<AdminRatingResponse>> resp = controller().listRatings(null, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTotalElements()).isEqualTo(0);
    }

    @Test
    void listRatings_withFlaggedFilter_passesFilter() {
        Page<RatingEntity> page = new PageImpl<>(List.of());
        when(ratingRepo.findAdminFiltered(eq(true), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);
        when(userRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<Page<AdminRatingResponse>> resp = controller().listRatings(true, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ratingRepo).findAdminFiltered(eq(true), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void listRatings_withScoreFilter_passesMinMax() {
        Page<RatingEntity> page = new PageImpl<>(List.of());
        when(ratingRepo.findAdminFiltered(isNull(), eq(3), eq(5), any(Pageable.class)))
                .thenReturn(page);
        when(userRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<Page<AdminRatingResponse>> resp = controller().listRatings(null, 3, 5, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(ratingRepo).findAdminFiltered(isNull(), eq(3), eq(5), any(Pageable.class));
    }

    @Test
    void listRatings_enrichesUserNames() {
        RatingEntity rating = new RatingEntity();
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        rating.setRaterId(fromId);
        rating.setRatedUserId(toId);
        rating.setStars(4);
        rating.setComment("Excellent");

        UserEntity fromUser = new UserEntity();
        fromUser.setFirstName("Alice");
        fromUser.setLastName("Martin");

        UserEntity toUser = new UserEntity();
        toUser.setFirstName("Bob");

        Page<RatingEntity> page = new PageImpl<>(List.of(rating));
        when(ratingRepo.findAdminFiltered(isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);
        when(userRepo.findAllById(any())).thenReturn(List.of(fromUser, toUser));

        ResponseEntity<Page<AdminRatingResponse>> resp = controller().listRatings(null, null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getContent()).hasSize(1);
    }

    // ---- excludeRating ----

    @Test
    void excludeRating_setsFlaggedTrueAndAudits() {
        UUID id = UUID.randomUUID();
        RatingEntity rating = new RatingEntity();
        assertThat(rating.isFlagged()).isFalse();

        when(ratingRepo.findById(id)).thenReturn(Optional.of(rating));
        when(ratingRepo.save(rating)).thenReturn(rating);
        when(userRepo.findAllById(any())).thenReturn(List.of());

        ResponseEntity<AdminRatingResponse> resp = controller().excludeRating(id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rating.isFlagged()).isTrue();
        verify(ratingRepo).save(rating);
        verify(auditService).log(eq("RATING"), eq(id), eq("RATING_EXCLUDED"), isNull(), anyMap());
    }

    @Test
    void excludeRating_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(ratingRepo.findById(id)).thenReturn(Optional.empty());

        DonyBusinessException ex = assertThrows(DonyBusinessException.class,
                () -> controller().excludeRating(id));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- deleteRating ----

    @Test
    void deleteRating_softDeletesAndAudits() {
        UUID id = UUID.randomUUID();
        RatingEntity rating = new RatingEntity();
        assertThat(rating.getDeletedAt()).isNull();

        when(ratingRepo.findById(id)).thenReturn(Optional.of(rating));

        ResponseEntity<Void> resp = controller().deleteRating(id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rating.getDeletedAt()).isNotNull();
        verify(ratingRepo).save(rating);
        verify(auditService).log(eq("RATING"), eq(id), eq("RATING_DELETED"), isNull(), anyMap());
    }

    @Test
    void deleteRating_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(ratingRepo.findById(id)).thenReturn(Optional.empty());

        DonyBusinessException ex = assertThrows(DonyBusinessException.class,
                () -> controller().deleteRating(id));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
