package com.dony.api.payments;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PaymentEntityV38MigrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void v38_adds_legacy_flag_and_charge_id_columns() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
            "WHERE LOWER(table_name) = 'payments' " +
            "AND LOWER(column_name) IN ('legacy_destination_charge','stripe_charge_id')",
            Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
