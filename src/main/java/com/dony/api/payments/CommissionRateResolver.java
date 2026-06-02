package com.dony.api.payments;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.config.DonyConfigProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SOURCE UNIQUE du taux de commission Dony effectif pour une transaction.
 *
 * <p>Règle (cf. {@code docs/specs/commission-rate-overrides-and-promo.md}) :
 * <ul>
 *   <li>Phase 1 : {@code min( override(voyageur), override(expéditeur), taux global )} —
 *       « le plus favorable », cohérent avec « l'expéditeur paie le moins ».</li>
 *   <li>Phase 2 : un code promo valide écrasera ce résultat (priorité 1).</li>
 * </ul>
 *
 * <p>Tous les consommateurs (charge {@code PaymentService}, affichage
 * {@code PriceGridService}, analytics) passent par ce résolveur avec le contexte dont
 * ils disposent (à la navigation, seul le voyageur est connu).
 */
@Service
public class CommissionRateResolver {

    private final UserRepository userRepository;
    private final DonyConfigProperties config;

    public CommissionRateResolver(UserRepository userRepository, DonyConfigProperties config) {
        this.userRepository = userRepository;
        this.config = config;
    }

    /** Taux global par défaut ({@code dony.commission.rate}). */
    public BigDecimal globalRate() {
        return config.commission().rate();
    }

    /** Taux effectif au moment de la navigation : seul le voyageur est connu. */
    public BigDecimal resolve(UUID travelerId) {
        return resolve(travelerId, null);
    }

    /**
     * Taux effectif pour le couple (voyageur, expéditeur). {@code senderId} peut être
     * {@code null} (navigation). Prend le taux le plus favorable parmi les overrides
     * présents et le taux global.
     */
    public BigDecimal resolve(UUID travelerId, UUID senderId) {
        BigDecimal rate = globalRate();
        rate = minNullable(rate, overrideOf(travelerId));
        rate = minNullable(rate, overrideOf(senderId));
        return rate;
    }

    private BigDecimal overrideOf(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(UserEntity::getCommissionRateOverride)
                .orElse(null);
    }

    private static BigDecimal minNullable(BigDecimal base, BigDecimal candidate) {
        return candidate == null ? base : base.min(candidate);
    }
}
