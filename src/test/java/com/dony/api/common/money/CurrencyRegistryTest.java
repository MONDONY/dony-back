package com.dony.api.common.money;

import com.dony.api.common.DonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrencyRegistryTest {

    @Mock private CurrencyRepository repository;
    private CurrencyRegistry registry;

    private static CurrencyEntity currency(String code, int minorUnit, BigDecimal peg, int increment) {
        CurrencyEntity e = new CurrencyEntity();
        e.setCode(code); e.setMinorUnit((short) minorUnit);
        e.setPegRateToEur(peg); e.setRoundingIncrement(increment); e.setEnabled(true);
        return e;
    }

    @BeforeEach
    void setUp() {
        when(repository.findByEnabledTrue()).thenReturn(List.of(
                currency("EUR", 2, null, 1),
                currency("XOF", 0, new BigDecimal("655.957"), 5)));
        registry = new CurrencyRegistry(repository);
    }

    @Test
    void minorUnitOfKnownCurrencies() {
        assertThat(registry.minorUnitOf("EUR")).isEqualTo(2);
        assertThat(registry.minorUnitOf("XOF")).isZero();
    }

    @Test
    void pegRateOfXofPresent_eurEmpty() {
        assertThat(registry.pegRateOf("XOF")).contains(new BigDecimal("655.957"));
        assertThat(registry.pegRateOf("EUR")).isEmpty();
    }

    @Test
    void unknownCurrencyThrowsBusinessException() {
        assertThatThrownBy(() -> registry.minorUnitOf("MAD"))
                .isInstanceOf(DonyBusinessException.class);
    }

    @Test
    void secondCallDoesNotHitDatabase() {
        registry.minorUnitOf("EUR");
        registry.minorUnitOf("XOF");
        verify(repository, times(1)).findByEnabledTrue();
    }
}
