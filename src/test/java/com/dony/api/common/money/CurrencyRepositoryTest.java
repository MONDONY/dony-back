package com.dony.api.common.money;

import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void seedCurrencies() {
        // Flyway is disabled in test profile (ddl-auto: create, H2 in-memory)
        // so we seed currencies directly here instead of relying on migrations
        repository.deleteAll();

        CurrencyEntity eur = new CurrencyEntity();
        eur.setCode("EUR");
        eur.setNumericCode((short) 978);
        eur.setMinorUnit((short) 2);
        eur.setSymbol("€");
        eur.setPegRateToEur(null);  // EUR is the base currency
        eur.setRoundingIncrement(1);
        eur.setEnabled(true);
        repository.saveAndFlush(eur);

        CurrencyEntity xof = new CurrencyEntity();
        xof.setCode("XOF");
        xof.setNumericCode((short) 952);
        xof.setMinorUnit((short) 0);
        xof.setSymbol("F CFA");
        xof.setPegRateToEur(new BigDecimal("655.957"));
        xof.setRoundingIncrement(5);
        xof.setEnabled(true);
        repository.saveAndFlush(xof);

        CurrencyEntity xaf = new CurrencyEntity();
        xaf.setCode("XAF");
        xaf.setNumericCode((short) 950);
        xaf.setMinorUnit((short) 0);
        xaf.setSymbol("F CFA");
        xaf.setPegRateToEur(new BigDecimal("655.957"));
        xaf.setRoundingIncrement(5);
        xaf.setEnabled(true);
        repository.saveAndFlush(xaf);
    }

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
