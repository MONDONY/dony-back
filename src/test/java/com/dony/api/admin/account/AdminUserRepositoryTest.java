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
        AdminUserEntity admin = new AdminUserEntity("firebase_uid_001", "admin1", AdminRole.SUPER_ADMIN);
        admin.setStatus(AdminStatus.ACTIVE);
        repository.save(admin);

        var found = repository.findByFirebaseUid("firebase_uid_001");

        assertThat(found).isPresent();
        assertThat(found.get().getLogin()).isEqualTo("admin1");
    }

    @Test
    void findByLogin() {
        AdminUserEntity admin = new AdminUserEntity("firebase_uid_002", "admin2", AdminRole.ADMIN);
        admin.setStatus(AdminStatus.ACTIVE);
        repository.save(admin);

        var found = repository.findByLogin("admin2");

        assertThat(found).isPresent();
        assertThat(found.get().getFirebaseUid()).isEqualTo("firebase_uid_002");
    }

    @Test
    void countByRoleAndStatus() {
        AdminUserEntity admin1 = new AdminUserEntity("firebase_uid_003", "admin3", AdminRole.SUPER_ADMIN);
        admin1.setStatus(AdminStatus.ACTIVE);
        repository.save(admin1);

        AdminUserEntity admin2 = new AdminUserEntity("firebase_uid_004", "admin4", AdminRole.SUPER_ADMIN);
        admin2.setStatus(AdminStatus.DISABLED);
        repository.save(admin2);

        long count = repository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);

        assertThat(count).isEqualTo(1);
    }
}
