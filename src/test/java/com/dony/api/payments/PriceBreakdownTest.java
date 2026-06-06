package com.dony.api.payments;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class PriceBreakdownTest {

    @Test void modelB_net35_rate12() {
        PriceBreakdown b = PriceBreakdown.fromNet(new BigDecimal("35"), new BigDecimal("0.12"));
        assertThat(b.net()).isEqualByComparingTo("35.00");
        assertThat(b.commission()).isEqualByComparingTo("4.20");
        assertThat(b.gross()).isEqualByComparingTo("39.20");
        assertThat(b.grossCents()).isEqualTo(3920L);
        assertThat(b.commissionCents()).isEqualTo(420L);
    }

    /** Vérifie que grossCents / commissionCents ne lancent pas ArithmeticException
     *  même si le taux a plus de 2 décimales (ex. 12.333...%). */
    @Test void cents_withFractionalRate_noArithmeticException() {
        // rate avec beaucoup de décimales — simule un taux futur non standard
        PriceBreakdown b = PriceBreakdown.fromNet(new BigDecimal("35"), new BigDecimal("0.123456789"));
        // L'important : pas d'exception, et les valeurs sont cohérentes
        assertThat(b.grossCents()).isGreaterThan(3900L);
        assertThat(b.commissionCents()).isGreaterThan(0L);
        assertThat(b.grossCents()).isEqualTo(b.commissionCents() + b.net().multiply(BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.HALF_UP).longValue());
    }
}
