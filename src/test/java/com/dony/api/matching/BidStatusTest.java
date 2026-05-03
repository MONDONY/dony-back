package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BidStatusTest {
    @Test
    void awaiting_payment_value_exists() {
        assertThat(BidStatus.valueOf("AWAITING_PAYMENT")).isEqualTo(BidStatus.AWAITING_PAYMENT);
    }
}
