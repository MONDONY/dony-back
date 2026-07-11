package com.dony.api.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Évalue les conditions d'une règle personnalisée (ruleType=CUSTOM) contre le
 * contexte d'un bid. Pur, sans accès base : toutes les valeurs nécessaires
 * sont résolues en amont dans {@link BidEvaluationContext}.
 *
 * <p>Sémantique fail-safe : dans le doute (valeur absente, {@code value} non
 * parsable, opérateur invalide pour le type de champ, field/operator inconnu,
 * liste de conditions vide), la règle ne matche PAS — on n'agit jamais sur un
 * doute. Les conditions sont combinées en ET strict.
 */
final class CustomRuleConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CustomRuleConditionEvaluator.class);

    private CustomRuleConditionEvaluator() {
    }

    /** true uniquement si TOUTES les conditions de la règle sont satisfaites (ET strict). */
    static boolean matches(AutomationRuleEntity rule, BidEvaluationContext ctx) {
        List<Map<String, Object>> conditions = rule.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return false;
        }
        for (Map<String, Object> condition : conditions) {
            if (!conditionSatisfied(rule, condition, ctx)) {
                return false;
            }
        }
        return true;
    }

    private static boolean conditionSatisfied(AutomationRuleEntity rule, Map<String, Object> condition,
                                              BidEvaluationContext ctx) {
        String field = asString(condition.get("field"));
        String operator = asString(condition.get("operator"));
        String value = asString(condition.get("value"));
        if (field == null || operator == null || value == null) {
            log.warn("Automation custom rule {}: condition incomplète {}", rule.getId(), condition);
            return false;
        }
        return switch (field) {
            case "sender_rating" -> numericCompare(rule, ctx.senderRating(), operator, value);
            case "weight_kg" -> numericCompare(rule, ctx.weightKg(), operator, value);
            case "capacity_free_kg" -> numericCompare(rule, ctx.capacityFreeKg(), operator, value);
            case "hours_before_departure" -> numericCompare(rule,
                    ctx.hoursBeforeDeparture() == null ? null : BigDecimal.valueOf(ctx.hoursBeforeDeparture()),
                    operator, value);
            case "corridor" -> textEquals(rule, ctx.corridor(), operator, value);
            case "content_type" -> textEquals(rule, ctx.contentCategory(), operator, value);
            default -> {
                log.warn("Automation custom rule {}: field inconnu '{}'", rule.getId(), field);
                yield false;
            }
        };
    }

    private static boolean numericCompare(AutomationRuleEntity rule, BigDecimal actual,
                                          String operator, String rawValue) {
        if (actual == null) {
            return false;
        }
        BigDecimal expected;
        try {
            expected = new BigDecimal(rawValue.trim());
        } catch (NumberFormatException e) {
            log.warn("Automation custom rule {}: valeur numérique non parsable '{}'", rule.getId(), rawValue);
            return false;
        }
        int cmp = actual.compareTo(expected);
        return switch (operator) {
            case "gte" -> cmp >= 0;
            case "lte" -> cmp <= 0;
            case "eq" -> cmp == 0;
            default -> {
                log.warn("Automation custom rule {}: operator inconnu '{}'", rule.getId(), operator);
                yield false;
            }
        };
    }

    private static boolean textEquals(AutomationRuleEntity rule, String actual,
                                      String operator, String rawValue) {
        if (!"eq".equals(operator)) {
            log.warn("Automation custom rule {}: operator '{}' invalide sur un champ texte", rule.getId(), operator);
            return false;
        }
        if (actual == null) {
            return false;
        }
        return normalize(actual).equals(normalize(rawValue));
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
