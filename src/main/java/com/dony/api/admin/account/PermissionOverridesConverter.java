package com.dony.api.admin.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.HashMap;
import java.util.Map;

@Converter
public class PermissionOverridesConverter implements AttributeConverter<Map<String, Boolean>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, Boolean> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize permission overrides", e);
        }
    }

    @Override
    public Map<String, Boolean> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || "{}".equals(dbData)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(dbData, new TypeReference<Map<String, Boolean>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize permission overrides", e);
        }
    }
}
