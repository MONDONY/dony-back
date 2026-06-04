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
}
