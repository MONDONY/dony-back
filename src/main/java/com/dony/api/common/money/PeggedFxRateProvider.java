package com.dony.api.common.money;

import com.dony.api.common.DonyBusinessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Conversion via parité fixe en base (zone CFA : 1 EUR = 655,957 XOF/XAF).
 * Ne répond QUE pour EUR ↔ devise arrimée. Retourne le montant NON arrondi :
 * l'appelant applique MoneyRounding selon le contexte (spec §5.6).
 *
 * NB : {@link CurrencyRegistry#pegRateOf(String)} lève {@link DonyBusinessException}
 * pour une devise totalement inconnue (jamais chargée en base) — ce provider ne doit
 * jamais propager cette exception : une devise flottante non encore supportée doit
 * simplement donner Optional.empty(), pas une erreur (spec §5.5).
 */
@Service
public class PeggedFxRateProvider implements FxRateProvider {

    private final CurrencyRegistry registry;

    public PeggedFxRateProvider(CurrencyRegistry registry) { this.registry = registry; }

    @Override
    public Optional<MoneyConversion> convert(Money source, String targetCurrency) {
        String from = source.currencyCode();
        if (from.equals(targetCurrency)) {
            // Valide que la devise identité est bien connue/activée avant de la traiter comme telle :
            // une devise jamais enregistrée ne doit pas non plus produire une fausse identité muette.
            if (!isKnownCurrency(from)) {
                return Optional.empty();
            }
            return Optional.of(new MoneyConversion(source,
                    new Money(source.amount(), targetCurrency), BigDecimal.ONE, "PEGGED"));
        }
        if ("EUR".equals(from)) {
            return pegRateOrEmpty(targetCurrency).map(peg -> new MoneyConversion(
                    source, new Money(source.amount().multiply(peg), targetCurrency), peg, "PEGGED"));
        }
        if ("EUR".equals(targetCurrency)) {
            return pegRateOrEmpty(from).map(peg -> new MoneyConversion(
                    source,
                    new Money(source.amount().divide(peg, 8, RoundingMode.HALF_UP), "EUR"),
                    peg, "PEGGED"));
        }
        return Optional.empty();
    }

    /** Comme {@link CurrencyRegistry#pegRateOf} mais renvoie empty (au lieu de lever) pour une devise inconnue. */
    private Optional<BigDecimal> pegRateOrEmpty(String code) {
        try {
            return registry.pegRateOf(code);
        } catch (DonyBusinessException e) {
            return Optional.empty();
        }
    }

    /** true si la devise est enregistrée/activée dans le registre (jamais de propagation d'exception). */
    private boolean isKnownCurrency(String code) {
        try {
            registry.minorUnitOf(code);
            return true;
        } catch (DonyBusinessException e) {
            return false;
        }
    }
}
