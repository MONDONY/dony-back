package com.dony.api.common.money;

import java.util.Optional;

/** Conversion de devise. Optional.empty() = « aucun montant local exact » — jamais de taux inventé (spec §5.5). */
public interface FxRateProvider {
    Optional<MoneyConversion> convert(Money source, String targetCurrency);
}
