package com.dony.api.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AutomationActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(AutomationActionExecutor.class);
    static final long DAILY_ACTION_CAP = 20;

    private final AutomationRuleRepository ruleRepository;
    private final AutomationHistoryRepository historyRepository;
    private final AutomationRuleService ruleService;

    public AutomationActionExecutor(AutomationRuleRepository ruleRepository,
                                    AutomationHistoryRepository historyRepository,
                                    AutomationRuleService ruleService) {
        this.ruleRepository = ruleRepository;
        this.historyRepository = historyRepository;
        this.ruleService = ruleService;
    }

    /**
     * Exécute une action liée à un bid (accept/reject) si le plafond quotidien
     * n'est pas atteint, en écrivant systématiquement une ligne d'historique.
     * Retourne true si l'action a été exécutée avec succès.
     *
     * <p><b>Volontairement PAS de {@code @Transactional} sur cette méthode.</b>
     * Le paramètre {@code action} invoque en pratique
     * {@code BidService.acceptBidBySystem}/{@code rejectBidBySystem}, qui sont
     * elles-mêmes {@code @Transactional}. Si cette méthode portait aussi
     * {@code @Transactional}, l'appel à {@code action.get()} deviendrait une
     * transaction *participante* de la même transaction physique : une
     * exception levée par l'action marquerait alors la transaction globale
     * {@code rollback-only}, et ce même si elle est attrapée juste après par
     * le {@code catch (Exception e)} ci-dessous. Résultat : l'écriture de
     * l'historique "FAILURE" semblerait réussir mais le commit final
     * échouerait avec {@code UnexpectedRollbackException}. Ne pas réintroduire
     * {@code @Transactional} ici. Chaque {@code writeHistory}/save individuel
     * garde donc sa transaction implicite propre (best-effort, pas
     * d'atomicité stricte action+historique sur ce chemin) — c'est un choix
     * assumé pour éviter ce partage de transaction avec l'action externe.
     */
    public boolean tryExecuteBidAction(AutomationRuleEntity rule, UUID travelerId, UUID bidId,
                                       String actionTaken, Supplier<Void> action) {
        if (ruleService.countTodayActions(travelerId) >= DAILY_ACTION_CAP) {
            disableRuleAndRecordCapReached(rule, travelerId, bidId, actionTaken);
            log.warn("Automation daily cap reached for traveler {}, rule {} disabled",
                    travelerId, rule.getName() != null ? rule.getName() : rule.getPresetRuleId());
            return false;
        }

        try {
            action.get();
            writeHistory(rule, travelerId, bidId, null, actionTaken, "SUCCESS", null);
            return true;
        } catch (Exception e) {
            writeHistory(rule, travelerId, bidId, null, actionTaken, "FAILURE", e.getMessage());
            log.warn("Automation action {} failed for bid {}: {}", actionTaken, bidId, e.getMessage());
            return false;
        }
    }

    /**
     * Sous-chemin "plafond atteint" : ne touche jamais à {@code action}.
     * ATTENTION : {@code @Transactional} ici est un no-op — cette méthode est
     * appelée en auto-invocation ({@code this.}) depuis {@link #tryExecuteBidAction},
     * donc l'appel ne passe jamais par le proxy transactionnel de Spring (limitation
     * connue de l'AOP par proxy). La désactivation de la règle et l'écriture de
     * l'historique restent donc best-effort, pas garanties atomiques — cohérent
     * avec les autres chemins de cette classe (SUCCESS/FAILURE), pas une régression.
     * Pour une vraie atomicité, il faudrait une auto-injection du proxy (bean
     * {@code @Lazy} self-injecté) ou un {@code TransactionTemplate} programmatique.
     */
    @Transactional
    void disableRuleAndRecordCapReached(AutomationRuleEntity rule, UUID travelerId, UUID bidId,
                                        String actionTaken) {
        rule.setEnabled(false);
        ruleRepository.save(rule);
        writeHistory(rule, travelerId, bidId, null, actionTaken, "CAP_REACHED",
                "Plafond quotidien de " + DAILY_ACTION_CAP + " actions atteint — règle désactivée.");
    }

    /** Enregistre une notification déclenchée par une règle (pas d'action bid associée). */
    public void recordNotification(AutomationRuleEntity rule, UUID travelerId, String actionTaken) {
        writeHistory(rule, travelerId, null, null, actionTaken, "SUCCESS", null);
    }

    private void writeHistory(AutomationRuleEntity rule, UUID travelerId, UUID bidId, UUID tripId,
                              String actionTaken, String result, String errorDetail) {
        AutomationHistoryEntity history = new AutomationHistoryEntity();
        history.setTravelerId(travelerId);
        history.setRuleId(rule.getId());
        history.setRuleLabel(rule.getName() != null ? rule.getName() : rule.getPresetRuleId());
        history.setBidId(bidId);
        history.setTripId(tripId);
        history.setActionTaken(actionTaken);
        history.setResult(result);
        history.setErrorDetail(errorDetail);
        history.setTriggeredAt(LocalDateTime.now(ZoneOffset.UTC));
        historyRepository.save(history);
    }
}
