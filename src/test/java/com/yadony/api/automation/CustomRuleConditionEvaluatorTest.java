package com.yadony.api.automation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomRuleConditionEvaluatorTest {

    private static final BidEvaluationContext FULL_CTX = new BidEvaluationContext(
            new BigDecimal("8"),          // weightKg
            "Paris → Dakar",              // corridor
            "Poissons",                   // contentCategory
            new BigDecimal("4.5"),        // senderRating
            new BigDecimal("12"),         // capacityFreeKg
            36L);                         // hoursBeforeDeparture

    private AutomationRuleEntity rule(List<Map<String, Object>> conditions) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setRuleType("CUSTOM");
        r.setName("Ma règle");
        r.setConditions(conditions);
        return r;
    }

    private Map<String, Object> cond(String field, String operator, String value) {
        return Map.of("field", field, "operator", operator, "value", value);
    }

    // --- Champs numériques : gte / lte / eq ---

    @Test
    void weightKg_gte_matchesWhenAboveOrEqual() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "gte", "8"))), FULL_CTX));
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "gte", "5"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "gte", "9"))), FULL_CTX));
    }

    @Test
    void weightKg_lte_matchesWhenBelowOrEqual() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "lte", "8"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "lte", "7"))), FULL_CTX));
    }

    @Test
    void senderRating_gte_worksOnRating() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("sender_rating", "gte", "4.0"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("sender_rating", "gte", "4.6"))), FULL_CTX));
    }

    @Test
    void capacityFreeKg_lte_worksOnCapacity() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("capacity_free_kg", "lte", "12"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("capacity_free_kg", "lte", "11"))), FULL_CTX));
    }

    @Test
    void hoursBeforeDeparture_lte_worksOnDerivedHours() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("hours_before_departure", "lte", "48"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("hours_before_departure", "lte", "24"))), FULL_CTX));
    }

    @Test
    void numericEq_ignoresBigDecimalScale() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "eq", "8.00"))), FULL_CTX));
    }

    // --- Champs texte : eq normalisé ---

    @Test
    void contentType_eq_isCaseAndWhitespaceInsensitive() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("content_type", "eq", " poissons "))), FULL_CTX));
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("content_type", "eq", "POISSONS"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("content_type", "eq", "poisson frais"))), FULL_CTX));
    }

    @Test
    void corridor_eq_matchesNormalized() {
        assertTrue(CustomRuleConditionEvaluator.matches(rule(List.of(cond("corridor", "eq", "paris → dakar"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("corridor", "eq", "Paris → Abidjan"))), FULL_CTX));
    }

    @Test
    void corridor_eq_isFullEqualityNotElementMatching_nonRegression() {
        // corridor est un scalaire ("Paris → Dakar"), contrairement à content_type qui est
        // une liste jointe par virgule. Une condition partielle ne doit jamais matcher.
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("corridor", "eq", "Paris"))), FULL_CTX));
    }

    // --- content_type : liste jointe par virgule, matching PAR ÉLÉMENT (FIX 1) ---
    //
    // BidEntity.contentCategory n'est pas un scalaire : le front (multi-sélection de chips,
    // create_bid_bottom_sheet.dart) le construit en `categories.join(', ')`, et
    // BidContentRules.assertNotRefused le reconsomme déjà en splittant sur ",". Une règle
    // "content_type = Poissons" doit matcher un bid "Vêtements, Poissons".

    private BidEvaluationContext ctxWithContent(String contentCategory) {
        return new BidEvaluationContext(
                new BigDecimal("8"), "Paris → Dakar", contentCategory,
                new BigDecimal("4.5"), new BigDecimal("12"), 36L);
    }

    @Test
    void contentType_multiValue_matchesWhenConditionIsOneOfTheItems() {
        assertTrue(CustomRuleConditionEvaluator.matches(
                rule(List.of(cond("content_type", "eq", "Poissons"))),
                ctxWithContent("Vêtements, Poissons")));
    }

    @Test
    void contentType_multiValue_matchesRegardlessOfCaseAndPosition() {
        assertTrue(CustomRuleConditionEvaluator.matches(
                rule(List.of(cond("content_type", "eq", "vêtements"))),
                ctxWithContent("Poissons, Vêtements")));
    }

    @Test
    void contentType_multiValue_noMatchWhenItemAbsent() {
        assertFalse(CustomRuleConditionEvaluator.matches(
                rule(List.of(cond("content_type", "eq", "Poissons"))),
                ctxWithContent("Vêtements, Documents")));
    }

    @Test
    void contentType_singleValue_stillMatches_nonRegression() {
        assertTrue(CustomRuleConditionEvaluator.matches(
                rule(List.of(cond("content_type", "eq", "Poissons"))),
                ctxWithContent("Poissons")));
    }

    @Test
    void contentType_multiValue_trimsWhitespaceAroundItems() {
        assertTrue(CustomRuleConditionEvaluator.matches(
                rule(List.of(cond("content_type", "eq", "poissons"))),
                ctxWithContent(" Poissons , Vêtements ")));
    }

    @Test
    void contentType_conditionMatchingWholeJoinedList_doesNotMatch_elementMatchingOnly() {
        // Comportement assumé : un voyageur qui saisirait bêtement la liste entière comme
        // valeur de condition ("Vêtements, Documents") ne doit PAS matcher un item unique,
        // car on matche élément par élément, jamais sur la chaîne entière reconstituée.
        assertFalse(CustomRuleConditionEvaluator.matches(
                rule(List.of(cond("content_type", "eq", "Vêtements, Documents"))),
                ctxWithContent("Vêtements, Documents")));
    }

    // --- ET strict ---

    @Test
    void allConditionsMustMatch_andStrict() {
        AutomationRuleEntity r = rule(List.of(
                cond("content_type", "eq", "Poissons"),
                cond("weight_kg", "gte", "5")));
        assertTrue(CustomRuleConditionEvaluator.matches(r, FULL_CTX));

        AutomationRuleEntity r2 = rule(List.of(
                cond("content_type", "eq", "Poissons"),
                cond("weight_kg", "gte", "20")));
        assertFalse(CustomRuleConditionEvaluator.matches(r2, FULL_CTX));
    }

    // --- Fail-safe ---

    @Test
    void emptyConditions_neverMatches() {
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of()), FULL_CTX));
    }

    @Test
    void nullContextValue_conditionNotSatisfied() {
        BidEvaluationContext ctx = new BidEvaluationContext(null, null, null, null, null, null);
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "gte", "1"))), ctx));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("content_type", "eq", "Poissons"))), ctx));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("sender_rating", "lte", "5"))), ctx));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("hours_before_departure", "gte", "0"))), ctx));
    }

    @Test
    void unparsableNumericValue_conditionNotSatisfied() {
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "gte", "lourd"))), FULL_CTX));
    }

    @Test
    void gteLteOnTextField_conditionNotSatisfied() {
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("content_type", "gte", "Poissons"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("corridor", "lte", "Paris → Dakar"))), FULL_CTX));
    }

    @Test
    void unknownFieldOrOperator_conditionNotSatisfied() {
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("declared_value", "gte", "100"))), FULL_CTX));
        assertFalse(CustomRuleConditionEvaluator.matches(rule(List.of(cond("weight_kg", "neq", "8"))), FULL_CTX));
    }

    @Test
    void incompleteCondition_missingKeys_notSatisfied() {
        AutomationRuleEntity r = rule(List.of(Map.of("field", "weight_kg")));
        assertFalse(CustomRuleConditionEvaluator.matches(r, FULL_CTX));
    }

    @Test
    void nullConditionInList_notSatisfied_noException() {
        // conditions JSONB malformé (ex. POST "conditions": [null]) : l'élément null ne
        // doit jamais provoquer de NPE, seulement un non-match fail-safe (FIX 2a).
        List<Map<String, Object>> conditions = new java.util.ArrayList<>();
        conditions.add(null);
        AutomationRuleEntity r = rule(conditions);
        assertFalse(assertDoesNotThrow(() -> CustomRuleConditionEvaluator.matches(r, FULL_CTX)));
    }
}
