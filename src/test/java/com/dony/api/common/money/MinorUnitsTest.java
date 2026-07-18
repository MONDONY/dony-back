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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinorUnitsTest {

    @Mock private CurrencyRepository repository;
    private CurrencyRegistry registry;

    @BeforeEach
    void setUp() {
        CurrencyEntity eur = new CurrencyEntity();
        eur.setCode("EUR"); eur.setMinorUnit((short) 2); eur.setRoundingIncrement(1); eur.setEnabled(true);
        CurrencyEntity xof = new CurrencyEntity();
        xof.setCode("XOF"); xof.setMinorUnit((short) 0); xof.setRoundingIncrement(5); xof.setEnabled(true);
        xof.setPegRateToEur(new BigDecimal("655.957"));
        when(repository.findByEnabledTrue()).thenReturn(List.of(eur, xof));
        registry = new CurrencyRegistry(repository);
    }

    @Test void eurToMinor()            { assertThat(MinorUnits.toMinor(new Money(new BigDecimal("12.00"), "EUR"), registry)).isEqualTo(1200); }
    @Test void xofToMinorNoTimes100()  { assertThat(MinorUnits.toMinor(new Money(new BigDecimal("7871"), "XOF"), registry)).isEqualTo(7871); }
    @Test void halfUpOnSubCent()       { assertThat(MinorUnits.toMinor(new Money(new BigDecimal("0.005"), "EUR"), registry)).isEqualTo(1); }
    @Test void xofDecimalsRoundedDown(){ assertThat(MinorUnits.toMinor(new Money(new BigDecimal("12.345"), "XOF"), registry)).isEqualTo(12); }

    @Test void exactAcceptsValidScale() { assertThat(MinorUnits.toMinorExact(new Money(new BigDecimal("12.34"), "EUR"), registry)).isEqualTo(1234); }
    @Test void exactRejectsExcessScale() {
        assertThatThrownBy(() -> MinorUnits.toMinorExact(new Money(new BigDecimal("0.005"), "EUR"), registry))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test void fromMinorXofNoDivide100() {
        assertThat(MinorUnits.fromMinor(7870, "XOF", registry).amount()).isEqualByComparingTo("7870");
    }
    @Test void fromMinorEur() {
        assertThat(MinorUnits.fromMinor(1234, "EUR", registry).amount()).isEqualByComparingTo("12.34");
    }

    @Test void roundTrip() {
        for (String code : new String[]{"EUR", "XOF"}) {
            Money m = new Money(code.equals("EUR") ? new BigDecimal("41.27") : new BigDecimal("7870"), code);
            assertThat(MinorUnits.fromMinor(MinorUnits.toMinor(m, registry), code, registry).amount())
                    .isEqualByComparingTo(m.amount());
        }
    }

    @Test void unknownCurrencyThrows() {
        assertThatThrownBy(() -> MinorUnits.toMinor(new Money(BigDecimal.ONE, "MAD"), registry))
                .isInstanceOf(DonyBusinessException.class);
    }
}
