package com.dony.api.automation;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidService;
import com.dony.api.matching.events.BidCreatedEvent;
import com.dony.api.notifications.NotificationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Écoute BidCreatedEvent (publié une fois le paiement de l'expéditeur autorisé,
 * bid en PAYMENT_ESCROWED) et exécute les règles d'automatisation actives du
 * voyageur propriétaire de l'annonce : refus auto (priorité), acceptation auto,
 * alerte dernière minute.
 *
 * <p><b>Volontairement PAS de {@code @Transactional} sur cette classe/méthode.</b>
 * {@link #onBidCreated} appelle {@link AutomationActionExecutor#tryExecuteBidAction}
 * qui, elle-même, invoque {@code BidService.acceptBidBySystem}/{@code rejectBidBySystem}
 * (chacune {@code @Transactional}). Ajouter {@code @Transactional} ici engloberait ces
 * appels dans une transaction physique partagée avec cette méthode : une exception levée
 * par l'action marquerait la transaction globale rollback-only même si
 * {@code tryExecuteBidAction} l'attrape en interne, et l'écriture d'historique
 * "FAILURE"/le commit final échoueraient avec {@code UnexpectedRollbackException} — le
 * même problème que celui corrigé en Task 2 sur {@code AutomationActionExecutor}. Ne pas
 * réintroduire {@code @Transactional} ici.
 */
@Component
public class AutomationBidListener {

    private static final Logger log = LoggerFactory.getLogger(AutomationBidListener.class);

    private final AutomationRuleRepository ruleRepository;
    private final AutomationActionExecutor executor;
    private final BidService bidService;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final NotificationDispatcher notificationDispatcher;

    public AutomationBidListener(AutomationRuleRepository ruleRepository,
                                 AutomationActionExecutor executor,
                                 BidService bidService,
                                 UserRepository userRepository,
                                 AnnouncementRepository announcementRepository,
                                 NotificationDispatcher notificationDispatcher) {
        this.ruleRepository = ruleRepository;
        this.executor = executor;
        this.bidService = bidService;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBidCreated(BidCreatedEvent event) {
        List<AutomationRuleEntity> rules =
                ruleRepository.findByTravelerIdOrderByCreatedAtAsc(event.getTravelerId());

        Optional<AutomationRuleEntity> rejectRule = findEnabledPreset(rules, "auto_reject_overweight");
        Optional<AutomationRuleEntity> acceptRule = findEnabledPreset(rules, "auto_accept_trusted");
        Optional<AutomationRuleEntity> lastMinuteRule = findEnabledPreset(rules, "alert_last_minute_bid");

        AnnouncementEntity announcement = announcementRepository.findById(event.getAnnouncementId())
                .orElse(null);
        if (announcement == null) {
            log.warn("Automation: announcement {} not found for bid {}", event.getAnnouncementId(), event.getBidId());
            return;
        }

        boolean rejected = false;
        if (rejectRule.isPresent() && event.getWeightKg() != null
                && event.getWeightKg().compareTo(announcement.getAvailableKg()) > 0) {
            AutomationRuleEntity rule = rejectRule.get();
            rejected = executor.tryExecuteBidAction(rule, event.getTravelerId(), event.getBidId(),
                    "AUTO_REJECT_OVERWEIGHT", () -> {
                        bidService.rejectBidBySystem(event.getBidId(), event.getTravelerId(),
                                "Le poids de ce colis dépasse la capacité restante sur ce trajet.");
                        return null;
                    });
        }

        if (!rejected && acceptRule.isPresent()) {
            AutomationRuleEntity rule = acceptRule.get();
            BigDecimal minRating = configNumber(rule, "minRating", new BigDecimal("4.0"));
            UserEntity sender = userRepository.findById(event.getSenderId()).orElse(null);
            boolean weightOk = event.getWeightKg() == null
                    || event.getWeightKg().compareTo(announcement.getAvailableKg()) <= 0;
            boolean ratingOk = sender != null && sender.getAverageRating() != null
                    && sender.getAverageRating().compareTo(minRating) >= 0;
            if (weightOk && ratingOk) {
                executor.tryExecuteBidAction(rule, event.getTravelerId(), event.getBidId(),
                        "AUTO_ACCEPT_TRUSTED", () -> {
                            bidService.acceptBidBySystem(event.getBidId(), event.getTravelerId());
                            return null;
                        });
            }
        }

        if (lastMinuteRule.isPresent() && announcement.getDepartureAt() != null) {
            AutomationRuleEntity rule = lastMinuteRule.get();
            int hoursBeforeDeparture = configInt(rule, "hoursBeforeDeparture", 48);
            long hoursUntilDeparture = java.time.Duration.between(
                    OffsetDateTime.now(), announcement.getDepartureAt()).toHours();
            if (hoursUntilDeparture >= 0 && hoursUntilDeparture < hoursBeforeDeparture) {
                notificationDispatcher.notifyUser(event.getTravelerId(),
                        "Offre de dernière minute",
                        "Une offre vient d'arriver pour un départ dans moins de "
                                + hoursBeforeDeparture + "h (" + event.getCorridor() + ").",
                        Map.of("type", "automation_last_minute", "bidId", event.getBidId().toString()));
                executor.recordNotification(rule, event.getTravelerId(), "ALERT_LAST_MINUTE_BID");
            }
        }
    }

    private Optional<AutomationRuleEntity> findEnabledPreset(List<AutomationRuleEntity> rules, String presetId) {
        return rules.stream()
                .filter(r -> presetId.equals(r.getPresetRuleId()) && r.isEnabled())
                .findFirst();
    }

    private BigDecimal configNumber(AutomationRuleEntity rule, String key, BigDecimal fallback) {
        Object v = rule.getAction() != null ? rule.getAction().get(key) : null;
        if (v == null) return fallback;
        return new BigDecimal(v.toString());
    }

    private int configInt(AutomationRuleEntity rule, String key, int fallback) {
        Object v = rule.getAction() != null ? rule.getAction().get(key) : null;
        if (v == null) return fallback;
        return Integer.parseInt(v.toString());
    }
}
