package com.dony.api.automation;

import com.dony.api.automation.dto.AutomationHistoryResponse;
import com.dony.api.automation.dto.AutomationRuleResponse;
import com.dony.api.automation.dto.CreateRuleRequest;
import com.dony.api.automation.dto.UpdatePresetRequest;
import com.dony.api.common.DonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutomationRuleServiceTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private AutomationHistoryRepository historyRepository;

    @InjectMocks private AutomationRuleService service;

    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID RULE_ID = UUID.randomUUID();

    @Nested
    @DisplayName("listRules")
    class ListRules {

        @Test
        void returnsAllSixPresets_evenWhenNoRulesStored() {
            when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(TRAVELER_ID))
                    .thenReturn(List.of());

            List<AutomationRuleResponse> rules = service.listRules(TRAVELER_ID);

            long presetCount = rules.stream().filter(r -> "PRESET".equals(r.ruleType())).count();
            assertThat(presetCount).isEqualTo(6);
        }

        @Test
        void presetsAreDisabledByDefault() {
            when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(TRAVELER_ID))
                    .thenReturn(List.of());

            List<AutomationRuleResponse> rules = service.listRules(TRAVELER_ID);

            rules.stream()
                    .filter(r -> "PRESET".equals(r.ruleType()))
                    .forEach(r -> assertThat(r.enabled()).isFalse());
        }

        @Test
        void presetEnabledState_reflectsStoredValue() throws Exception {
            AutomationRuleEntity stored = new AutomationRuleEntity();
            stored.setTravelerId(TRAVELER_ID);
            stored.setRuleType("PRESET");
            stored.setPresetRuleId("auto_accept_trusted");
            stored.setName("label");
            stored.setEnabled(true);
            stored.setConditions(List.of());
            stored.setAction(Map.of());

            when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(TRAVELER_ID))
                    .thenReturn(List.of(stored));

            List<AutomationRuleResponse> rules = service.listRules(TRAVELER_ID);

            AutomationRuleResponse preset = rules.stream()
                    .filter(r -> "auto_accept_trusted".equals(r.id()))
                    .findFirst().orElseThrow();
            assertThat(preset.enabled()).isTrue();
        }

        @Test
        void includesCustomRules() throws Exception {
            AutomationRuleEntity custom = buildCustomRule();
            when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(TRAVELER_ID))
                    .thenReturn(List.of(custom));

            List<AutomationRuleResponse> rules = service.listRules(TRAVELER_ID);

            assertThat(rules.stream().anyMatch(r -> "CUSTOM".equals(r.ruleType()))).isTrue();
            assertThat(rules.stream().filter(r -> "CUSTOM".equals(r.ruleType())).count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("createRule")
    class CreateRule {

        @Test
        void persistsAndReturnsCustomRule() {
            AutomationRuleEntity saved = new AutomationRuleEntity();
            saved.setRuleType("CUSTOM");
            saved.setName("Refuser moins de 3 kg");
            saved.setConditions(List.of(Map.of("field", "weight_kg", "operator", "lte", "value", "3")));
            saved.setAction(Map.of("type", "auto_reject"));
            saved.setEnabled(true);
            try { setField(saved, "id", RULE_ID); } catch (Exception ignored) {}

            when(ruleRepository.save(any())).thenReturn(saved);

            CreateRuleRequest req = new CreateRuleRequest(
                    "Refuser moins de 3 kg",
                    List.of(Map.of("field", "weight_kg", "operator", "lte", "value", "3")),
                    Map.of("type", "auto_reject")
            );

            AutomationRuleResponse result = service.createRule(TRAVELER_ID, req);

            assertThat(result.name()).isEqualTo("Refuser moins de 3 kg");
            assertThat(result.ruleType()).isEqualTo("CUSTOM");
        }
    }

    @Nested
    @DisplayName("updatePreset")
    class UpdatePreset {

        @Test
        void createsNewEntityWhenPresetNotStored() {
            when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(TRAVELER_ID))
                    .thenReturn(List.of());
            AutomationRuleEntity created = new AutomationRuleEntity();
            created.setPresetRuleId("auto_close_full");
            created.setEnabled(false);
            created.setRuleType("PRESET");
            created.setName("label");
            created.setAction(Map.of());
            created.setConditions(List.of());
            when(ruleRepository.save(any())).thenReturn(created);

            service.updatePreset(TRAVELER_ID, "auto_close_full",
                    new UpdatePresetRequest(true, null));

            verify(ruleRepository, times(2)).save(any());
        }

        @Test
        void updatesExistingPreset() throws Exception {
            AutomationRuleEntity existing = new AutomationRuleEntity();
            existing.setPresetRuleId("auto_close_full");
            existing.setEnabled(false);
            existing.setRuleType("PRESET");
            existing.setName("label");
            existing.setAction(Map.of());
            existing.setConditions(List.of());

            when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(TRAVELER_ID))
                    .thenReturn(List.of(existing));
            when(ruleRepository.save(any())).thenReturn(existing);

            AutomationRuleResponse result = service.updatePreset(
                    TRAVELER_ID, "auto_close_full", new UpdatePresetRequest(true, null));

            assertThat(result.ruleType()).isEqualTo("PRESET");
        }
    }

    @Nested
    @DisplayName("deleteRule")
    class DeleteRule {

        @Test
        void softDeletesRule() throws Exception {
            AutomationRuleEntity rule = buildCustomRule();
            when(ruleRepository.findByIdAndTravelerId(RULE_ID, TRAVELER_ID))
                    .thenReturn(Optional.of(rule));
            when(ruleRepository.save(any())).thenReturn(rule);

            service.deleteRule(TRAVELER_ID, RULE_ID);

            ArgumentCaptor<AutomationRuleEntity> captor = ArgumentCaptor.forClass(AutomationRuleEntity.class);
            verify(ruleRepository).save(captor.capture());
            assertThat(captor.getValue().getDeletedAt()).isNotNull();
        }

        @Test
        void throws404_whenRuleNotFound() {
            when(ruleRepository.findByIdAndTravelerId(RULE_ID, TRAVELER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteRule(TRAVELER_ID, RULE_ID))
                    .isInstanceOf(DonyBusinessException.class);
        }
    }

    @Nested
    @DisplayName("listHistory")
    class ListHistory {

        @Test
        void returnsEmptyList_whenNoHistory() {
            when(historyRepository.findByTravelerIdOrderByTriggeredAtDesc(
                    eq(TRAVELER_ID), any(Pageable.class)))
                    .thenReturn(List.of());

            List<AutomationHistoryResponse> history = service.listHistory(TRAVELER_ID);

            assertThat(history).isEmpty();
        }

        @Test
        void mapsHistoryEntry_correctly() {
            AutomationHistoryEntity entry = new AutomationHistoryEntity();
            try { setField(entry, "id", UUID.randomUUID()); } catch (Exception ignored) {}
            entry.setTravelerId(TRAVELER_ID);
            entry.setRuleLabel("Auto-reject");
            entry.setActionTaken("Bid rejeté automatiquement");
            entry.setResult("SUCCESS");
            entry.setTriggeredAt(LocalDateTime.now());

            when(historyRepository.findByTravelerIdOrderByTriggeredAtDesc(
                    eq(TRAVELER_ID), any(Pageable.class)))
                    .thenReturn(List.of(entry));

            List<AutomationHistoryResponse> history = service.listHistory(TRAVELER_ID);

            assertThat(history).hasSize(1);
            assertThat(history.get(0).ruleLabel()).isEqualTo("Auto-reject");
            assertThat(history.get(0).result()).isEqualTo("SUCCESS");
        }
    }

    // ---- Helpers ----

    private AutomationRuleEntity buildCustomRule() throws Exception {
        AutomationRuleEntity rule = new AutomationRuleEntity();
        setField(rule, "id", RULE_ID);
        rule.setTravelerId(TRAVELER_ID);
        rule.setRuleType("CUSTOM");
        rule.setName("Ma règle");
        rule.setConditions(List.of());
        rule.setAction(Map.of("type", "auto_reject"));
        rule.setEnabled(true);
        setField(rule, "createdAt", LocalDateTime.now());
        return rule;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
