package com.dony.api.payments.cash;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

@Converter
public class PaymentMethodSetConverter implements AttributeConverter<Set<PaymentMethod>, String> {

    @Override
    public String convertToDatabaseColumn(Set<PaymentMethod> attribute) {
        if (attribute == null || attribute.isEmpty()) return "{STRIPE}";
        String inner = attribute.stream().map(Enum::name).collect(Collectors.joining(","));
        return "{" + inner + "}";
    }

    @Override
    public Set<PaymentMethod> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return EnumSet.of(PaymentMethod.STRIPE);
        String stripped = dbData.replaceAll("[{}]", "");
        if (stripped.isBlank()) return EnumSet.of(PaymentMethod.STRIPE);
        Set<PaymentMethod> result = Arrays.stream(stripped.split(","))
                .filter(s -> !s.isBlank())
                .map(PaymentMethod::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PaymentMethod.class)));
        return result.isEmpty() ? EnumSet.of(PaymentMethod.STRIPE) : result;
    }
}
