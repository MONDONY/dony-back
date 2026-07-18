package com.dony.api.common.money;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeggedFxRateProviderTest {

    @Mock private CurrencyRepository repository;
    private CurrencyRegistry registry;
    private PeggedFxRateProvider provider;

    @BeforeEach
    void setUp() {
        CurrencyEntity eur = new CurrencyEntity();
        eur.setCode("EUR"); eur.setMinorUnit((short) 2); eur.setRoundingIncrement(1); eur.setEnabled(true);
        CurrencyEntity xof = new CurrencyEntity();
        xof.setCode("XOF"); xof.setMinorUnit((short) 0); xof.setRoundingIncrement(5); xof.setEnabled(true);
        xof.setPegRateToEur(new BigDecimal("655.957"));
        when(repository.findByEnabledTrue()).thenReturn(List.of(eur, xof));
        registry = new CurrencyRegistry(repository);
        provider = new PeggedFxRateProvider(registry);
    }

    @Test
    void eurToXofUsesPeg() {
        MoneyConversion conv = provider.convert(new Money(new BigDecimal("12"), "EUR"), "XOF").orElseThrow();
        assertThat(conv.target().amount()).isEqualByComparingTo("7871.484");
        assertThat(conv.rate()).isEqualByComparingTo("655.957");
        assertThat(conv.rateSource()).isEqualTo("PEGGED");
    }

    @Test
    void xofToEurDividesByPeg() {
        MoneyConversion conv = provider.convert(new Money(new BigDecimal("7870"), "XOF"), "EUR").orElseThrow();
        assertThat(conv.target().amount()).isEqualByComparingTo(
                new BigDecimal("7870").divide(new BigDecimal("655.957"), 8, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void sameCurrencyIsIdentity() {
        MoneyConversion conv = provider.convert(new Money(new BigDecimal("12"), "EUR"), "EUR").orElseThrow();
        assertThat(conv.target().amount()).isEqualByComparingTo("12");
        assertThat(conv.rate()).isEqualByComparingTo("1");
    }

    @Test
    void floatingCurrencyReturnsEmpty() {
        // EUR n'a pas de peg : EUR → EUR ok (identité) mais X → Y sans peg → empty
        assertThat(provider.convert(new Money(BigDecimal.ONE, "EUR"), "MAD")).isEmpty();
    }
}
