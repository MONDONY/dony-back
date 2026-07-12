package com.dony.api.subscriptions;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class TravelerSubscriptionRepositoryTest {

    @Autowired
    TravelerSubscriptionRepository repo;

    @Autowired
    UserRepository userRepository;

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

    @Test
    void findEnrichedBySenderId_fallsBackToPlaceholderName_whenTravelerHasNoFirstOrLastName() {
        UserEntity traveler = new UserEntity();
        traveler.setFirebaseUid("uid-" + UUID.randomUUID());
        // first_name / last_name left null on purpose — reproduces the "drissa"-style
        // seed account that has no name fields populated.
        traveler = userRepository.save(traveler);

        UUID sender = UUID.randomUUID();
        TravelerSubscriptionEntity sub = new TravelerSubscriptionEntity();
        sub.setSenderId(sender);
        sub.setTravelerId(traveler.getId());
        repo.save(sub);

        List<Object[]> rows = repo.findEnrichedBySenderId(sender);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).isEqualTo("Voyageur");
    }
}
