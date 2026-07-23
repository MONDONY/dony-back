package com.dony.api.payments.cash;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Comme {@link PaymentMethodSetConverter} mais PRESERVE null / vide -> NULL en base.
 * Utilisé pour negotiation_threads.available_payment_methods où NULL signifie
 * "trajet pas encore lié / SET non calculé" (à ne pas confondre avec {STRIPE}).
 * autoApply=false pour ne pas écraser le converter par défaut des autres Set&lt;PaymentMethod&gt;.
 */
@Converter(autoApply = false)
public class NullablePaymentMethodSetConverter implements AttributeConverter<Set<PaymentMethod>, String> {

    @Override
    public String convertToDatabaseColumn(Set<PaymentMethod> attribute) {
        if (attribute == null || attribute.isEmpty()) return null;
        String inner = attribute.stream().map(Enum::name).collect(Collectors.joining(","));
        return "{" + inner + "}";
    }

    @Override
    public Set<PaymentMethod> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        String stripped = dbData.replaceAll("[{}]", "");
        if (stripped.isBlank()) return null;
        Set<PaymentMethod> result = Arrays.stream(stripped.split(","))
                .filter(s -> !s.isBlank())
                .map(PaymentMethod::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PaymentMethod.class)));
        return result.isEmpty() ? null : result;
    }
}
