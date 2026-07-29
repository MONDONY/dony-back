package com.yadony.api.config;

import com.yadony.api.config.dto.ContentCategoryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ContentCatalogTest {

    @Test
    void catalog_hasElevenCategories() {
        assertThat(ContentCatalog.CATEGORIES).hasSize(11);
    }

    @Test
    void noLabelContainsComma_becauseBidContentCategoryIsCommaJoined() {
        // Invariant critique : bids.content_category encode plusieurs catégories jointes
        // par virgule. Un libellé virgulé casserait le split(",") de BidContentRules et
        // de CustomRuleConditionEvaluator.
        for (ContentCategoryResponse c : ContentCatalog.CATEGORIES) {
            assertThat(c.label()).doesNotContain(",");
        }
    }

    @Test
    void codesAreUnique() {
        Set<String> codes = ContentCatalog.CATEGORIES.stream()
                .map(ContentCategoryResponse::code)
                .collect(Collectors.toSet());
        assertThat(codes).hasSize(ContentCatalog.CATEGORIES.size());
    }

    @Test
    void labelsAreUnique() {
        Set<String> labels = ContentCatalog.CATEGORIES.stream()
                .map(ContentCategoryResponse::label)
                .collect(Collectors.toSet());
        assertThat(labels).hasSize(ContentCatalog.CATEGORIES.size());
    }

    @Test
    void everyCategoryHasCodeLabelAndEmoji() {
        for (ContentCategoryResponse c : ContentCatalog.CATEGORIES) {
            assertThat(c.code()).isNotBlank();
            assertThat(c.label()).isNotBlank();
            assertThat(c.emoji()).isNotBlank();
        }
    }

    @Test
    void catalogContainsTheExpectedLabels() {
        List<String> labels = ContentCatalog.CATEGORIES.stream()
                .map(ContentCategoryResponse::label)
                .toList();
        assertThat(labels).containsExactly(
                "Documents & administratif",
                "Alimentation sèche",
                "Produits frais / périssables",
                "Cosmétiques & parfums",
                "Vêtements & tissus",
                "Chaussures",
                "Médicaments traditionnels",
                "Téléphone & électronique",
                "Livres",
                "Cadeaux & jouets",
                "Autre");
    }

    @Test
    void catalogIsImmutable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> ContentCatalog.CATEGORIES.add(
                        new ContentCategoryResponse("X", "X", "X")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
