package com.dony.api.alerts.dto;

import com.dony.api.alerts.AlertDirection;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorridorAlertRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void validRequest_hasNoViolations() {
        CorridorAlertRequest req = new CorridorAlertRequest(
                "Paris", "FR", "Bamako", "ML",
                null, null, null, List.of(), AlertDirection.TRAVELER_WANTS_PACKAGES, null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void blankDepartureCity_isViolation() {
        CorridorAlertRequest req = new CorridorAlertRequest(
                "  ", "FR", "Bamako", "ML",
                null, null, null, List.of(), AlertDirection.TRAVELER_WANTS_PACKAGES, null);
        assertThat(validator.validate(req))
                .anyMatch(v -> v.getPropertyPath().toString().equals("departureCity"));
    }

    @Test
    void blankArrivalCity_isViolation() {
        CorridorAlertRequest req = new CorridorAlertRequest(
                "Paris", "FR", "", "ML",
                null, null, null, List.of(), AlertDirection.TRAVELER_WANTS_PACKAGES, null);
        assertThat(validator.validate(req))
                .anyMatch(v -> v.getPropertyPath().toString().equals("arrivalCity"));
    }

    @Test
    void nullDirection_isViolation() {
        CorridorAlertRequest req = new CorridorAlertRequest(
                "Paris", "FR", "Bamako", "ML",
                null, null, null, List.of(), null, null);
        assertThat(validator.validate(req))
                .anyMatch(v -> v.getPropertyPath().toString().equals("direction"));
    }
}
