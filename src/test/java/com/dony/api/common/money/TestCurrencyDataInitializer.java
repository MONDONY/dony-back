package com.dony.api.common.money;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

/**
 * Initializes seed data for currencies table in test profile.
 * This is necessary because Flyway is disabled in test profile (H2 doesn't support PostgreSQL PL/pgSQL).
 */
@Component
@ActiveProfiles("test")
public class TestCurrencyDataInitializer {

    private final CurrencyRepository repository;

    public TestCurrencyDataInitializer(CurrencyRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeTestData() {
        // Only seed if table is empty
        if (repository.count() == 0) {
            seedCurrencies();
        }
    }

    private void seedCurrencies() {
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
}
