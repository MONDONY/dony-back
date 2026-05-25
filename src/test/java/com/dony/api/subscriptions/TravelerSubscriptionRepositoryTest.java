package com.dony.api.subscriptions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class TravelerSubscriptionRepositoryTest {

    @Autowired
    TravelerSubscriptionRepository repo;

    @Test
    void findActiveBySenderIdAndTravelerId_returnsSavedSubscription() {
        UUID sender = UUID.randomUUID();
        UUID traveler = UUID.randomUUID();
        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(sender);
        sub.setTravelerId(traveler);
        repo.save(sub);

        assertThat(repo.findBySenderIdAndTravelerId(sender, traveler)).isPresent();
        assertThat(repo.existsBySenderIdAndTravelerId(sender, traveler)).isTrue();
    }
}
