package com.dony.api.payments.cash;

import static com.dony.api.payments.cash.PaymentMethod.CASH;
import static com.dony.api.payments.cash.PaymentMethod.STRIPE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NullablePaymentMethodSetConverterTest {

    private final NullablePaymentMethodSetConverter converter = new NullablePaymentMethodSetConverter();

    @Test
    void convertToDatabaseColumn_null_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToDatabaseColumn_empty_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(EnumSet.noneOf(PaymentMethod.class))).isNull();
    }

    @Test
    void convertToDatabaseColumn_nonEmpty_returnsBracedCsv() {
        assertThat(converter.convertToDatabaseColumn(EnumSet.of(STRIPE, CASH))).isEqualTo("{STRIPE,CASH}");
    }

    @Test
    void convertToEntityAttribute_null_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_emptyString_returnsNull() {
        assertThat(converter.convertToEntityAttribute("")).isNull();
    }

    @Test
    void convertToEntityAttribute_emptyBraces_returnsNull() {
        assertThat(converter.convertToEntityAttribute("{}")).isNull();
    }

    @Test
    void convertToEntityAttribute_roundtrip_returnsSet() {
        Set<PaymentMethod> result = converter.convertToEntityAttribute("{STRIPE,CASH}");
        assertThat(result).isEqualTo(EnumSet.of(STRIPE, CASH));
    }
}
