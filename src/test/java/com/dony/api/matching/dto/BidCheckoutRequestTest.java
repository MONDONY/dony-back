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

    // C3 : deux libellés canoniques joints par virgule dépassent 50 caractères
    // ("Produits frais / périssables, Médicaments traditionnels" = 55). Avant le
    // correctif (@Size(max=50)), cette requête aurait échoué la validation bean
    // → HTTP 400 sur POST /bids/checkout dès qu'un front envoie une multi-sélection
    // canonique de 2-3 catégories.
    @Test
    void multi_selection_of_canonical_categories_over_50_chars_is_accepted() {
        String joined = "Produits frais / périssables, Médicaments traditionnels";
        assertThat(joined).hasSizeGreaterThan(50);

        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), new BigDecimal("2.50"), new BigDecimal("100.00"),
            "Colis multi-catégories", joined,
            "Aïssatou", "+221771234567", true, null, null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void three_canonical_categories_joined_is_accepted() {
        String joined = "Documents & administratif, Téléphone & électronique, Vêtements & tissus";

        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), new BigDecimal("2.50"), new BigDecimal("100.00"),
            "Colis multi-catégories", joined,
            "Aïssatou", "+221771234567", true, null, null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void content_category_over_500_chars_is_rejected() {
        String tooLong = "x".repeat(501);
        BidCheckoutRequest req = new BidCheckoutRequest(
            UUID.randomUUID(), new BigDecimal("2.50"), new BigDecimal("100.00"),
            "x", tooLong, "n", "+221", true, null, null);

        assertThat(validator.validate(req)).isNotEmpty();
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
