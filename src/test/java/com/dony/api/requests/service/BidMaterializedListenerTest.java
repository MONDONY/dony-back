package com.dony.api.requests.service;

import com.dony.api.matching.events.BidMaterializedEvent;
import com.dony.api.requests.entity.NegotiationThreadEntity;
import com.dony.api.requests.repository.NegotiationThreadRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BidMaterializedListener")
class BidMaterializedListenerTest {

    @Mock private NegotiationThreadRepository threadRepository;

    @InjectMocks private BidMaterializedListener listener;

    private static final UUID THREAD_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    @Test
    @DisplayName("event → thread chargé, materializedBidId stampé et sauvegardé")
    void onBidMaterialized_stampsAndSaves() {
        NegotiationThreadEntity thread = new NegotiationThreadEntity();
        when(threadRepository.findById(THREAD_ID)).thenReturn(Optional.of(thread));

        listener.onBidMaterialized(new BidMaterializedEvent(THREAD_ID, BID_ID));

        assertThat(thread.getMaterializedBidId()).isEqualTo(BID_ID);
        verify(threadRepository).save(thread);
    }

    @Test
    @DisplayName("thread introuvable → no-op (pas de save)")
    void onBidMaterialized_threadMissing_noOp() {
        when(threadRepository.findById(THREAD_ID)).thenReturn(Optional.empty());

        listener.onBidMaterialized(new BidMaterializedEvent(THREAD_ID, BID_ID));

        verify(threadRepository, never()).save(any());
    }
}
