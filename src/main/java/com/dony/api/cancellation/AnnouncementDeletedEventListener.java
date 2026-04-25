package com.dony.api.cancellation;

import com.dony.api.matching.events.AnnouncementDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class AnnouncementDeletedEventListener {

    private final RematchSuggestionRepository rematchSuggestionRepository;

    public AnnouncementDeletedEventListener(RematchSuggestionRepository rematchSuggestionRepository) {
        this.rematchSuggestionRepository = rematchSuggestionRepository;
    }

    @EventListener
    @Transactional
    public void onAnnouncementDeleted(AnnouncementDeletedEvent event) {
        List<RematchSuggestionEntity> suggestions =
                rematchSuggestionRepository.findByAnnouncementId(event.announcementId());
        for (RematchSuggestionEntity suggestion : suggestions) {
            suggestion.softDelete();
            rematchSuggestionRepository.save(suggestion);
        }
    }
}