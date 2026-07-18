package com.dony.api.common.money;

import com.dony.api.common.DonyBusinessException;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Source unique des propriétés de devise (spec devise §5.4). Cache Caffeine
 * TTL 1 h — acceptable car R2 gèle tout montant transactionnel avant débit :
 * un cache en retard ne décale que l'affichage indicatif.
 */
@Service
public class CurrencyRegistry {

    private static final String CACHE_KEY = "all";

    private final LoadingCache<String, Map<String, CurrencyEntity>> cache;

    public CurrencyRegistry(CurrencyRepository repository) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build(key -> repository.findByEnabledTrue().stream()
                        .collect(Collectors.toMap(CurrencyEntity::getCode, Function.identity())));
    }

    public List<CurrencyEntity> enabledCurrencies() {
        return List.copyOf(cache.get(CACHE_KEY).values());
    }

    public int minorUnitOf(String code)          { return get(code).getMinorUnit(); }
    public int roundingIncrementOf(String code)  { return get(code).getRoundingIncrement(); }

    public Optional<BigDecimal> pegRateOf(String code) {
        return Optional.ofNullable(get(code).getPegRateToEur());
    }

    private CurrencyEntity get(String code) {
        CurrencyEntity e = cache.get(CACHE_KEY).get(code);
        if (e == null) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "unknown-currency", "Unknown Currency",
                    "Devise inconnue ou désactivée : " + code);
        }
        return e;
    }
}
