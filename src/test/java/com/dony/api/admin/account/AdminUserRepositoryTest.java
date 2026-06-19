package com.dony.api.admin.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class AdminUserRepositoryTest {

    @Autowired
    private AdminUserRepository repository;

    @Test
    void findByFirebaseUid() {
        AdminUserEntity admin = new AdminUserEntity("firebase_uid_001", "admin1", "SUPER_ADMIN");
        admin.setStatus("ACTIVE");
        repository.save(admin);

        var found = repository.findByFirebaseUid("firebase_uid_001");

        assertThat(found).isPresent();
        assertThat(found.get().getLogin()).isEqualTo("admin1");
    }

    @Test
    void findByLogin() {
        AdminUserEntity admin = new AdminUserEntity("firebase_uid_002", "admin2", "ADMIN");
        admin.setStatus("ACTIVE");
        repository.save(admin);

        var found = repository.findByLogin("admin2");

        assertThat(found).isPresent();
        assertThat(found.get().getFirebaseUid()).isEqualTo("firebase_uid_002");
    }

    @Test
    void countByRoleAndStatusAndDeletedAtIsNull() {
        AdminUserEntity admin1 = new AdminUserEntity("firebase_uid_003", "admin3", "SUPER_ADMIN");
        admin1.setStatus("ACTIVE");
        repository.save(admin1);

        AdminUserEntity admin2 = new AdminUserEntity("firebase_uid_004", "admin4", "SUPER_ADMIN");
        admin2.setStatus("DISABLED");
        repository.save(admin2);

        long count = repository.countByRoleAndStatusAndDeletedAtIsNull("SUPER_ADMIN", "ACTIVE");

        assertThat(count).isEqualTo(1);
    }
}
