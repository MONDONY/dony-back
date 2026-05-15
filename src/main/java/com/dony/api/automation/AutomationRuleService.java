package com.dony.api.automation;

import com.dony.api.automation.dto.AutomationHistoryResponse;
import com.dony.api.automation.dto.AutomationRuleResponse;
import com.dony.api.automation.dto.CreateRuleRequest;
import com.dony.api.automation.dto.UpdatePresetRequest;
import com.dony.api.common.DonyBusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AutomationRuleService {

    private static final int HISTORY_PAGE_SIZE = 50;

    private static final List<String> PRESET_IDS = List.of(
            "auto_accept_trusted",
            "auto_reject_overweight",
            "auto_close_full",
            "alert_capacity_free",
            "notify_loyal_senders",
            "alert_last_minute_bid"
    );

    private final AutomationRuleRepository ruleRepository;
    private final AutomationHistoryRepository historyRepository;

    public AutomationRuleService(AutomationRuleRepository ruleRepository,
                                 AutomationHistoryRepository historyRepository) {
        this.ruleRepository = ruleRepository;
        this.historyRepository = historyRepository;
    }

    public List<AutomationRuleResponse> listRules(UUID travelerId) {
        List<AutomationRuleEntity> stored = ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId);
        var result = new java.util.ArrayList<AutomationRuleResponse>();

        for (String presetId : PRESET_IDS) {
            AutomationRuleEntity entity = stored.stream()
                    .filter(r -> presetId.equals(r.getPresetRuleId()))
                    .findFirst().orElse(null);
            boolean enabled = entity != null && entity.isEnabled();
            Map<String, Object> config = entity != null && entity.getAction() != null
                    ? entity.getAction() : Map.of();
            result.add(buildPresetResponse(presetId, enabled, config));
        }

        stored.stream()
                .filter(r -> "CUSTOM".equals(r.getRuleType()))
                .map(this::toCustomResponse)
                .forEach(result::add);

        return result;
    }

    @Transactional
    public AutomationRuleResponse createRule(UUID travelerId, CreateRuleRequest req) {
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setTravelerId(travelerId);
        rule.setRuleType("CUSTOM");
        rule.setName(req.name());
        rule.setConditions(req.conditions() != null ? req.conditions() : List.of());
        rule.setAction(req.action() != null ? req.action() : Map.of());
        rule.setEnabled(true);
        return toCustomResponse(ruleRepository.save(rule));
    }

    @Transactional
    public AutomationRuleResponse updatePreset(UUID travelerId, String presetRuleId,
                                               UpdatePresetRequest req) {
        AutomationRuleEntity rule = ruleRepository
                .findByTravelerIdOrderByCreatedAtAsc(travelerId)
                .stream()
                .filter(r -> presetRuleId.equals(r.getPresetRuleId()))
                .findFirst()
                .orElseGet(() -> initPresetRule(travelerId, presetRuleId));

        rule.setEnabled(req.enabled());
        if (req.config() != null) {
            rule.setAction(req.config());
        }
        ruleRepository.save(rule);
        return buildPresetResponse(presetRuleId, rule.isEnabled(),
                rule.getAction() != null ? rule.getAction() : Map.of());
    }

    @Transactional
    public AutomationRuleResponse updateCustomRule(UUID travelerId, UUID ruleId,
                                                   CreateRuleRequest req) {
        AutomationRuleEntity rule = findOwned(travelerId, ruleId);
        rule.setName(req.name());
        rule.setConditions(req.conditions() != null ? req.conditions() : List.of());
        rule.setAction(req.action() != null ? req.action() : Map.of());
        return toCustomResponse(ruleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(UUID travelerId, UUID ruleId) {
        AutomationRuleEntity rule = findOwned(travelerId, ruleId);
        rule.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        ruleRepository.save(rule);
    }

    public List<AutomationHistoryResponse> listHistory(UUID travelerId) {
        return historyRepository
                .findByTravelerIdOrderByTriggeredAtDesc(travelerId,
                        PageRequest.of(0, HISTORY_PAGE_SIZE))
                .stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    private AutomationRuleEntity findOwned(UUID travelerId, UUID ruleId) {
        return ruleRepository.findByIdAndTravelerId(ruleId, travelerId)
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "rule-not-found",
                        "Rule Not Found", "Règle introuvable."));
    }

    private AutomationRuleEntity initPresetRule(UUID travelerId, String presetRuleId) {
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setTravelerId(travelerId);
        rule.setRuleType("PRESET");
        rule.setPresetRuleId(presetRuleId);
        rule.setName(resolvePresetLabel(presetRuleId));
        rule.setConditions(List.of());
        rule.setAction(Map.of());
        rule.setEnabled(false);
        return ruleRepository.save(rule);
    }

    private AutomationRuleResponse buildPresetResponse(String presetRuleId, boolean enabled,
                                                       Map<String, Object> config) {
        PresetMeta meta = resolvePresetMeta(presetRuleId);
        return new AutomationRuleResponse(
                presetRuleId, "PRESET", enabled,
                meta.label(), meta.description(), meta.isConfigurable(), config,
                null, null, null, null
        );
    }

    private AutomationRuleResponse toCustomResponse(AutomationRuleEntity rule) {
        return new AutomationRuleResponse(
                rule.getId().toString(), "CUSTOM", rule.isEnabled(),
                null, null, null, null,
                rule.getName(),
                rule.getConditions() != null ? rule.getConditions() : List.of(),
                rule.getAction() != null ? rule.getAction() : Map.of(),
                rule.getCreatedAt() != null ? rule.getCreatedAt().toString() : null
        );
    }

    private AutomationHistoryResponse toHistoryResponse(AutomationHistoryEntity h) {
        return new AutomationHistoryResponse(
                h.getId().toString(),
                h.getTriggeredAt().toString(),
                h.getRuleId() != null ? h.getRuleId().toString() : null,
                h.getRuleLabel(),
                h.getBidId() != null ? h.getBidId().toString() : null,
                h.getTripId() != null ? h.getTripId().toString() : null,
                h.getActionTaken(),
                h.getResult()
        );
    }

    private record PresetMeta(String label, String description, boolean isConfigurable) {}

    private PresetMeta resolvePresetMeta(String presetRuleId) {
        return switch (presetRuleId) {
            case "auto_accept_trusted" -> new PresetMeta(
                    "Accepter automatiquement les expéditeurs de confiance",
                    "Accepte les offres des expéditeurs avec une note ≥ seuil configuré.", true);
            case "auto_reject_overweight" -> new PresetMeta(
                    "Refuser automatiquement les colis trop lourds",
                    "Refuse les offres dépassant le poids disponible.", false);
            case "auto_close_full" -> new PresetMeta(
                    "Fermer automatiquement quand c'est complet",
                    "Ferme l'annonce dès que la capacité est atteinte.", false);
            case "alert_capacity_free" -> new PresetMeta(
                    "Alerter quand de la capacité se libère",
                    "Notifie les expéditeurs favoris dès qu'une place se libère.", true);
            case "notify_loyal_senders" -> new PresetMeta(
                    "Notifier les expéditeurs fidèles",
                    "Envoie une notification aux expéditeurs avec qui vous avez déjà voyagé.", false);
            case "alert_last_minute_bid" -> new PresetMeta(
                    "Alerter en cas d'offre de dernière minute",
                    "Notifie dès qu'une offre arrive à moins de X heures du départ.", true);
            default -> new PresetMeta(presetRuleId, "", false);
        };
    }

    private String resolvePresetLabel(String presetRuleId) {
        return resolvePresetMeta(presetRuleId).label();
    }
}
