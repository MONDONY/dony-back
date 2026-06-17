package com.dony.api.matching;

import com.dony.api.common.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidPhotoCleanupSchedulerTest {

    @Mock private BidPhotoRepository photoRepository;
    @Mock private BidRepository bidRepository;
    @Mock private StorageService storageService;
    @InjectMocks private BidPhotoCleanupScheduler scheduler;

    private BidPhotoEntity photo(UUID bidId, String key) {
        return new BidPhotoEntity(bidId, key, 0);
    }

    private BidEntity bidWithStatus(BidStatus status) {
        BidEntity b = new BidEntity();
        b.setStatus(status);
        return b;
    }

    @Test
    void purges_deletesFileThenRow() {
        BidPhotoEntity p = photo(UUID.randomUUID(), "bids/s/1.jpg");
        when(photoRepository.findByStatus(BidPhotoStatus.ACTIVE)).thenReturn(List.of());
        when(photoRepository.findByStatus(BidPhotoStatus.DELETING)).thenReturn(List.of(p));

        scheduler.purgeDeletingPhotos();

        verify(storageService).deleteFile("bids/s/1.jpg");
        verify(photoRepository).delete(p);
    }

    @Test
    void purges_removesRowEvenIfS3DeleteFails() {
        BidPhotoEntity p = photo(UUID.randomUUID(), "bids/s/1.jpg");
        when(photoRepository.findByStatus(BidPhotoStatus.ACTIVE)).thenReturn(List.of());
        when(photoRepository.findByStatus(BidPhotoStatus.DELETING)).thenReturn(List.of(p));
        doThrow(new RuntimeException("gone")).when(storageService).deleteFile(any());

        scheduler.purgeDeletingPhotos();

        verify(photoRepository).delete(p);
    }

    @Test
    void defensiveSweep_marksActivePhotoOfTerminalBidDeleting() {
        UUID bidId = UUID.randomUUID();
        BidPhotoEntity p = photo(bidId, "bids/s/1.jpg");
        when(photoRepository.findByStatus(BidPhotoStatus.ACTIVE)).thenReturn(List.of(p));
        when(photoRepository.findByStatus(BidPhotoStatus.DELETING)).thenReturn(List.of());
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bidWithStatus(BidStatus.IN_TRANSIT)));

        scheduler.purgeDeletingPhotos();

        assertThat(p.getStatus()).isEqualTo(BidPhotoStatus.DELETING);
        verify(photoRepository).save(p);
    }

    @Test
    void defensiveSweep_marksActivePhotoOfMissingBidDeleting() {
        UUID bidId = UUID.randomUUID();
        BidPhotoEntity p = photo(bidId, "bids/s/1.jpg");
        when(photoRepository.findByStatus(BidPhotoStatus.ACTIVE)).thenReturn(List.of(p));
        when(photoRepository.findByStatus(BidPhotoStatus.DELETING)).thenReturn(List.of());
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        scheduler.purgeDeletingPhotos();

        assertThat(p.getStatus()).isEqualTo(BidPhotoStatus.DELETING);
    }

    @Test
    void defensiveSweep_leavesActivePhotoOfAcceptedBid() {
        UUID bidId = UUID.randomUUID();
        BidPhotoEntity p = photo(bidId, "bids/s/1.jpg");
        when(photoRepository.findByStatus(BidPhotoStatus.ACTIVE)).thenReturn(List.of(p));
        when(photoRepository.findByStatus(BidPhotoStatus.DELETING)).thenReturn(List.of());
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bidWithStatus(BidStatus.ACCEPTED)));

        scheduler.purgeDeletingPhotos();

        assertThat(p.getStatus()).isEqualTo(BidPhotoStatus.ACTIVE);
    }
}
