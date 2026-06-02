package com.dony.api.promo;

import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Validation et rachat des codes promo.
 * Appelé par {@link com.dony.api.common.CommissionRateResolver} (source unique du taux effectif).
 */
@Service
public class PromoService {

    private static final Logger log = LoggerFactory.getLogger(PromoService.class);

    private final PromoCodeRepository promoCodeRepository;
    private final PromoRedemptionRepository redemptionRepository;
    private final AuditService auditService;

    public PromoService(PromoCodeRepository promoCodeRepository,
                        PromoRedemptionRepository redemptionRepository,
                        AuditService auditService) {
        this.promoCodeRepository = promoCodeRepository;
        this.redemptionRepository = redemptionRepository;
        this.auditService = auditService;
    }

    /**
     * Valide un code promo et retourne le taux associé.
     * Lève une {@link DonyBusinessException} si le code est invalide pour l'utilisateur donné.
     *
     * @param code   Code promo brut (insensible à la casse).
     * @param userId ID de l'utilisateur qui soumet le code (expéditeur au checkout).
     * @param target Rôle de l'utilisateur (SENDER ou TRAVELER) pour vérifier la cible du promo.
     */
    public BigDecimal validateAndGetRate(String code, UUID userId, PromoCodeTarget target) {
        if (code == null || code.isBlank()) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-not-found", "Promo Not Found", "Code promo introuvable");
        }
        PromoCodeEntity promo = promoCodeRepository.findByCode(code.toUpperCase())
                .orElseThrow(() -> new DonyBusinessException(HttpStatus.NOT_FOUND,
                        "promo-not-found", "Promo Not Found",
                        "Code promo introuvable : " + code.toUpperCase()));

        if (promo.getStatus() != PromoCodeStatus.ACTIVE) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-expired", "Promo Expired", "Ce code promo n'est plus actif");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (promo.getValidFrom() != null && now.isBefore(promo.getValidFrom())) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-expired", "Promo Expired", "Ce code promo n'est pas encore valide");
        }
        if (promo.getValidTo() != null && now.isAfter(promo.getValidTo())) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-expired", "Promo Expired", "Ce code promo a expiré");
        }

        if (promo.getMaxRedemptions() != null && promo.getRedeemedCount() >= promo.getMaxRedemptions()) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-limit-reached", "Promo Limit Reached",
                    "Ce code promo a atteint son nombre maximum d'utilisations");
        }

        if (promo.getTarget() != PromoCodeTarget.ANY && promo.getTarget() != target) {
            throw new DonyBusinessException(HttpStatus.FORBIDDEN,
                    "promo-not-eligible", "Promo Not Eligible",
                    "Ce code promo n'est pas disponible pour votre profil");
        }

        long userRedemptions = redemptionRepository.countByPromoCodeIdAndUserId(promo.getId(), userId);
        if (userRedemptions >= promo.getPerUserLimit()) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "promo-limit-reached", "Promo Limit Reached",
                    "Vous avez déjà utilisé ce code promo le nombre maximum de fois autorisé");
        }

        return promo.getRate();
    }

    /**
     * Enregistre l'utilisation du promo et incrémente le compteur global.
     * Idempotent : si la rédemption (promoCodeId, bidId) existe déjà, retourne sans double-décompte.
     */
    @Transactional
    public PromoRedemptionEntity redeem(String code, UUID userId, UUID bidId, BigDecimal appliedRate) {
        PromoCodeEntity promo = promoCodeRepository.findByCode(code.toUpperCase()).orElseThrow();

        if (redemptionRepository.existsByPromoCodeIdAndBidId(promo.getId(), bidId)) {
            log.info("Promo code {} already redeemed for bid {} (idempotent skip)", code, bidId);
            return redemptionRepository.findByPromoCodeIdAndBidId(promo.getId(), bidId).orElseThrow();
        }

        // Verrou pessimiste pour incrémenter redeemedCount sans race condition.
        PromoCodeEntity locked = promoCodeRepository.findByIdForUpdate(promo.getId()).orElseThrow();
        locked.setRedeemedCount(locked.getRedeemedCount() + 1);
        promoCodeRepository.save(locked);

        PromoRedemptionEntity redemption = new PromoRedemptionEntity();
        redemption.setPromoCodeId(promo.getId());
        redemption.setUserId(userId);
        redemption.setBidId(bidId);
        redemption.setAppliedRate(appliedRate);
        redemption.setRedeemedAt(LocalDateTime.now(ZoneOffset.UTC));
        PromoRedemptionEntity saved = redemptionRepository.save(redemption);

        auditService.log("PROMO", promo.getId(), "PROMO_CODE_REDEEMED", userId,
                Map.of("code", code.toUpperCase(), "bidId", bidId.toString(),
                        "rate", appliedRate.toPlainString()));

        return saved;
    }
}
