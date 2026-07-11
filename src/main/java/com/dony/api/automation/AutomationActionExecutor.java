package com.dony.api.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
     */
    public boolean tryExecuteBidAction(AutomationRuleEntity rule, UUID travelerId, UUID bidId,
                                       String actionTaken, Supplier<Void> action) {
        if (ruleService.countTodayActions(travelerId) >= DAILY_ACTION_CAP) {
            rule.setEnabled(false);
            ruleRepository.save(rule);
            writeHistory(rule, travelerId, bidId, null, actionTaken, "CAP_REACHED",
                    "Plafond quotidien de " + DAILY_ACTION_CAP + " actions atteint — règle désactivée.");
            log.warn("Automation daily cap reached for traveler {}, rule {} disabled",
                    travelerId, rule.getPresetRuleId());
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
