package com.dony.api.matching.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class BidCheckoutRequestTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void valid_request_has_no_violations() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(),
            new BigDecimal("2.50"),
            new BigDecimal("100.00"),
            "Médicaments", "OTHER",
            "Aïssatou", "+221771234567", true);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void weight_must_be_positive() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), BigDecimal.ZERO, new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void announcement_id_required() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            null, new BigDecimal("2"), new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void disclaimer_must_be_signed_true() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("100"),
            "x", "OTHER", "n", "+221", false);
        assertThat(validator.validate(req)).isNotEmpty();
    }
}
