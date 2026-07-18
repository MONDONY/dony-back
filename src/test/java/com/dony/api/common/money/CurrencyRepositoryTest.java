package com.dony.api.common.money;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class CurrencyRepositoryTest {

    @Autowired private CurrencyRepository repository;

    @Test
    void seedContainsEurXofXaf() {
        assertThat(repository.findById("EUR")).isPresent();
        assertThat(repository.findById("XOF")).isPresent();
        assertThat(repository.findById("XAF")).isPresent();
    }

    @Test
    void xofHasZeroMinorUnitAndPeg() {
        CurrencyEntity xof = repository.findById("XOF").orElseThrow();
        assertThat(xof.getMinorUnit()).isZero();
        assertThat(xof.getPegRateToEur()).isEqualByComparingTo(new BigDecimal("655.957"));
        assertThat(xof.getRoundingIncrement()).isEqualTo(5);
    }

    @Test
    void eurHasTwoDecimalsNoPeg() {
        CurrencyEntity eur = repository.findById("EUR").orElseThrow();
        assertThat(eur.getMinorUnit()).isEqualTo((short) 2);
        assertThat(eur.getPegRateToEur()).isNull();
    }
}
