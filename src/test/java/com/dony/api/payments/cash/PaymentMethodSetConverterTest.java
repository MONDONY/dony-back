package com.dony.api.payments.cash;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentMethodSetConverterTest {

    private final PaymentMethodSetConverter conv = new PaymentMethodSetConverter();

    @Test
    void serializesSingleValue() {
        assertThat(conv.convertToDatabaseColumn(EnumSet.of(PaymentMethod.STRIPE))).isEqualTo("{STRIPE}");
    }

    @Test
    void serializesMultipleValues() {
        Set<PaymentMethod> set = EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH);
        String out = conv.convertToDatabaseColumn(set);
        assertThat(out).containsAnyOf("{STRIPE,CASH}", "{CASH,STRIPE}");
    }

    @Test
    void deserializesPostgresArrayFormat() {
        assertThat(conv.convertToEntityAttribute("{STRIPE,CASH}"))
                .containsExactlyInAnyOrder(PaymentMethod.STRIPE, PaymentMethod.CASH);
    }

    @Test
    void defaultsToStripeWhenNull() {
        assertThat(conv.convertToEntityAttribute(null)).containsExactly(PaymentMethod.STRIPE);
    }

    @Test
    void defaultsToStripeWhenEmpty() {
        assertThat(conv.convertToEntityAttribute("{}")).containsExactly(PaymentMethod.STRIPE);
    }
}
