package com.dony.api.cancellation;

import com.dony.api.matching.events.AnnouncementDeletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnouncementDeletedEventListenerTest {

    @Mock private RematchSuggestionRepository rematchSuggestionRepository;

    private AnnouncementDeletedEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new AnnouncementDeletedEventListener(rematchSuggestionRepository);
    }

    @Test
    void softDeletesAllSuggestionsForDeletedAnnouncement() {
        UUID announcementId = UUID.randomUUID();
        RematchSuggestionEntity s1 = new RematchSuggestionEntity();
        s1.setAnnouncementId(announcementId);
        s1.setCancellationId(UUID.randomUUID());

        RematchSuggestionEntity s2 = new RematchSuggestionEntity();
        s2.setAnnouncementId(announcementId);
        s2.setCancellationId(UUID.randomUUID());

        when(rematchSuggestionRepository.findByAnnouncementId(announcementId))
                .thenReturn(List.of(s1, s2));

        listener.onAnnouncementDeleted(new AnnouncementDeletedEvent(announcementId, UUID.randomUUID()));

        verify(rematchSuggestionRepository, times(2)).save(any(RematchSuggestionEntity.class));
    }

    @Test
    void noSuggestionsIsNoOp() {
        UUID announcementId = UUID.randomUUID();
        when(rematchSuggestionRepository.findByAnnouncementId(announcementId))
                .thenReturn(List.of());

        listener.onAnnouncementDeleted(new AnnouncementDeletedEvent(announcementId, UUID.randomUUID()));

        verify(rematchSuggestionRepository, never()).save(any());
    }
}
