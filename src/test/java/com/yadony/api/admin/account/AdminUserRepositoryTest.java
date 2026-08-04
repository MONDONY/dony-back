package com.yadony.api.admin.account;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminUserRepositoryTest {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private AdminUserRepository repository;

    @Test
    void findByFirebaseUid() {
        AdminUserEntity admin = new AdminUserEntity("firebase_uid_001", "admin1@yadony.com", AdminRole.SUPER_ADMIN);
        admin.setStatus(AdminStatus.ACTIVE);
        repository.save(admin);

        var found = repository.findByFirebaseUid("firebase_uid_001");

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("admin1@yadony.com");
    }

    @Test
    void findByEmailIsCaseInsensitive() {
        AdminUserEntity admin = new AdminUserEntity("firebase_uid_002", "admin@yadony.com", AdminRole.ADMIN);
        admin.setStatus(AdminStatus.ACTIVE);
        repository.save(admin);

        assertThat(repository.findByEmailIgnoreCase("ADMIN@YADONY.COM")).isPresent();
    }

    @Test
    void onlyOneSuperAdminCanBePersisted() {
        repository.saveAndFlush(new AdminUserEntity("firebase_uid_root", "root@yadony.com", AdminRole.SUPER_ADMIN));

        assertThatThrownBy(() -> repository.saveAndFlush(
                new AdminUserEntity("firebase_uid_root_2", "other@yadony.com", AdminRole.SUPER_ADMIN)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void countByRoleAndStatus() {
        AdminUserEntity admin1 = new AdminUserEntity("firebase_uid_003", "admin3@yadony.com", AdminRole.SUPER_ADMIN);
        admin1.setStatus(AdminStatus.ACTIVE);
        repository.save(admin1);

        AdminUserEntity admin2 = new AdminUserEntity("firebase_uid_004", "admin4@yadony.com", AdminRole.ADMIN);
        admin2.setStatus(AdminStatus.DISABLED);
        repository.save(admin2);

        long count = repository.countByRoleAndStatus(AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);

        assertThat(count).isEqualTo(1);
    }
}
