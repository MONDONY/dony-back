package com.dony.api.matching;

import com.dony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class MobileMoneyPaymentMethodTest {

    @Test
    void paymentMethod_hasWaveAndOrangeMoney() {
        assertThat(PaymentMethod.valueOf("WAVE")).isEqualTo(PaymentMethod.WAVE);
        assertThat(PaymentMethod.valueOf("ORANGE_MONEY")).isEqualTo(PaymentMethod.ORANGE_MONEY);
    }

    @Test
    void resolvePaymentMethods_waveAccepted_noCommissionCardRequired() {
        Set<PaymentMethod> all = EnumSet.allOf(PaymentMethod.class);
        assertThat(all).contains(PaymentMethod.WAVE, PaymentMethod.ORANGE_MONEY);
    }
}
