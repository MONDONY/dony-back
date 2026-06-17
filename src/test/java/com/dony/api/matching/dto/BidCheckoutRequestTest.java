package com.dony.api.matching.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
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
            "Aïssatou", "+221771234567", true, null, null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void weight_must_not_be_negative() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), new BigDecimal("-1"), new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true, null, null);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void announcement_id_required() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            null, new BigDecimal("2"), new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true, null, null);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void disclaimer_must_be_signed_true() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), new BigDecimal("2"), new BigDecimal("100"),
            "x", "OTHER", "n", "+221", false, null, null);
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void null_weight_is_valid_for_grid_mode() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), null, new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true,
            null,
            List.of(new BidGridItemRequest(UUID.randomUUID(), 2)));
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void grid_item_with_zero_quantity_is_invalid() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), null, new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true,
            null,
            List.of(new BidGridItemRequest(UUID.randomUUID(), 0)));
        assertThat(validator.validate(req)).isNotEmpty();
    }

    @Test
    void grid_item_with_null_announcement_item_id_is_invalid() {
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), null, new BigDecimal("100"),
            "x", "OTHER", "n", "+221", true,
            null,
            List.of(new BidGridItemRequest(null, 1)));
        assertThat(validator.validate(req)).isNotEmpty();
    }
}
