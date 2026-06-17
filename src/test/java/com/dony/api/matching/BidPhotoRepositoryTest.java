package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class BidPhotoRepositoryTest {

    @Autowired private BidPhotoRepository repository;

    @Test
    void savesAndQueriesByBidAndStatusOrderedByPosition() {
        UUID bidId = UUID.randomUUID();
        repository.save(new BidPhotoEntity(bidId, "bids/s/2_b.jpg", 1));
        repository.save(new BidPhotoEntity(bidId, "bids/s/1_a.jpg", 0));

        List<BidPhotoEntity> active =
                repository.findByBidIdAndStatusOrderByPositionAsc(bidId, BidPhotoStatus.ACTIVE);

        assertThat(active).hasSize(2);
        assertThat(active.get(0).getObjectKey()).isEqualTo("bids/s/1_a.jpg");
        assertThat(active.get(1).getObjectKey()).isEqualTo("bids/s/2_b.jpg");
    }

    @Test
    void markDeletingMovesRowOutOfActiveQuery() {
        UUID bidId = UUID.randomUUID();
        BidPhotoEntity p = repository.save(new BidPhotoEntity(bidId, "bids/s/1.jpg", 0));
        p.markDeleting();
        repository.save(p);
        repository.flush();

        BidPhotoEntity reloaded = repository.findById(p.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BidPhotoStatus.DELETING);
        assertThat(reloaded.getDeletingSince()).isNotNull();

        assertThat(repository.findByBidIdAndStatusOrderByPositionAsc(bidId, BidPhotoStatus.ACTIVE)).isEmpty();
        assertThat(repository.findByStatus(BidPhotoStatus.DELETING)).extracting(BidPhotoEntity::getId).contains(p.getId());
    }
}
