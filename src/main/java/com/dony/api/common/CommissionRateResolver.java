package com.dony.api.common;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.config.DonyConfigProperties;
import com.dony.api.promo.PromoCodeTarget;
import com.dony.api.promo.PromoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SOURCE UNIQUE du taux de commission Dony effectif pour une transaction.
 *
 * <p>Placé dans {@code common/} car il s'agit de logique partagée (matching,
 * payments, promo).
 *
 * <p>Règle (cf. {@code docs/specs/commission-rate-overrides-and-promo.md}) :
 * <ol>
 *   <li><b>Phase 2</b> — si {@code promoCode} non null et valide → taux promo (priorité 1, écrase tout).</li>
 *   <li><b>Phase 1</b> — sinon : {@code min( override(voyageur), override(expéditeur), global )}.</li>
 * </ol>
 */
@Service
public class CommissionRateResolver {

    private static final Logger log = LoggerFactory.getLogger(CommissionRateResolver.class);

    private final UserRepository userRepository;
    private final DonyConfigProperties config;
    private final PromoService promoService;

    public CommissionRateResolver(UserRepository userRepository,
                                  DonyConfigProperties config,
                                  PromoService promoService) {
        this.userRepository = userRepository;
        this.config = config;
        this.promoService = promoService;
    }

    /** Taux global par défaut ({@code dony.commission.rate}). */
    public BigDecimal globalRate() {
        return config.commission().rate();
    }

    /** Taux effectif à la navigation : seul le voyageur est connu (pas de promo). */
    public BigDecimal resolve(UUID travelerId) {
        return resolve(travelerId, null);
    }

    /**
     * Taux effectif pour le couple (voyageur, expéditeur) sans code promo.
     * {@code senderId} peut être {@code null}.
     */
    public BigDecimal resolve(UUID travelerId, UUID senderId) {
        return resolve(travelerId, senderId, null, null);
    }

    /**
     * Taux effectif complet (Phase 2) : promo > overrides > global.
     *
     * @param travelerId ID du voyageur (non null).
     * @param senderId   ID de l'expéditeur (nullable — non connu à la navigation).
     * @param promoCode  Code promo brut (nullable).
     * @param senderId2  Pour la validation du promo (userId = l'expéditeur, target SENDER).
     *                   Alias {@code senderId} — le promo est entré par l'expéditeur.
     */
    public BigDecimal resolve(UUID travelerId, UUID senderId, String promoCode) {
        return resolve(travelerId, senderId, promoCode, senderId);
    }

    /**
     * Résolution complète avec contexte utilisateur explicite pour la validation promo.
     * <p>Utilisé dans les contextes où l'ID de l'utilisateur qui entre le code promo
     * diffère du senderId (rare, mais couvert pour extensibilité Phase 3+).
     * <p>Si {@code promoCode} est non null : valide strictement et retourne le taux promo
     * (lève {@link com.dony.api.common.DonyBusinessException} si invalide — laisser remonter
     * dans le contexte devis, attraper et logger dans le contexte paiement).
     */
    public BigDecimal resolve(UUID travelerId, UUID senderId, String promoCode, UUID promoUserId) {
        if (promoCode != null && !promoCode.isBlank() && promoUserId != null) {
            BigDecimal promoRate = promoService.validateAndGetRate(
                    promoCode, promoUserId, PromoCodeTarget.SENDER);
            return promoRate;
        }
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
