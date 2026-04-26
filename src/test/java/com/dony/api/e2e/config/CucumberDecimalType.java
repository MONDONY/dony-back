package com.dony.api.e2e.config;

import io.cucumber.java.ParameterType;

/**
 * Custom Cucumber parameter type {decimal} that parses decimal numbers using
 * Double.parseDouble(), which is locale-independent. Replaces the built-in
 * {double} type that uses NumberFormat (locale-sensitive).
 */
public class CucumberDecimalType {

    @ParameterType("[-+]?\\d+\\.\\d+|[-+]?\\d+")
    public Double decimal(String number) {
        return Double.parseDouble(number);
    }
}
