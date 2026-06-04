package com.dony.api.requests.entity;

public enum ParcelSize {
    SMALL, MEDIUM, LARGE;

    public static ParcelSize fromWeightKg(java.math.BigDecimal kg) {
        if (kg.compareTo(new java.math.BigDecimal("5")) <= 0) return SMALL;
        if (kg.compareTo(new java.math.BigDecimal("15")) <= 0) return MEDIUM;
        return LARGE;
    }
}
