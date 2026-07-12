package com.dony.api.automation;

import com.dony.api.common.DonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutomationActionExecutorTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private AutomationHistoryRepository historyRepository;
    @Mock private AutomationRuleService ruleService;

    private AutomationActionExecutor executor;
    private UUID travelerId;
    private UUID bidId;
    private AutomationRuleEntity rule;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        executor = new AutomationActionExecutor(ruleRepository, historyRepository, ruleService);
        travelerId = UUID.randomUUID();
        bidId = UUID.randomUUID();
        rule = new AutomationRuleEntity();
        rule.setTravelerId(travelerId);
        rule.setPresetRuleId("auto_accept_trusted");
        rule.setEnabled(true);
    }

    @Test
    void tryExecuteBidAction_runsActionAndWritesHistoryOnSuccess() {
        when(ruleService.countTodayActions(travelerId)).thenReturn(0L);

        boolean executed = executor.tryExecuteBidAction(rule, travelerId, bidId,
                "AUTO_ACCEPT", () -> null);

        assertThat(executed).isTrue();
        verify(historyRepository).save(argThat(h ->
                h.getTravelerId().equals(travelerId)
                && h.getBidId().equals(bidId)
                && h.getActionTaken().equals("AUTO_ACCEPT")
                && h.getResult().equals("SUCCESS")));
    }

    @Test
    void tryExecuteBidAction_writesFailureHistoryWhenActionThrows() {
        when(ruleService.countTodayActions(travelerId)).thenReturn(0L);

        boolean executed = executor.tryExecuteBidAction(rule, travelerId, bidId,
                "AUTO_ACCEPT", () -> { throw new DonyBusinessException(
                        org.springframework.http.HttpStatus.CONFLICT, "capacity-insufficient",
                        "x", "Capacité insuffisante"); });

        assertThat(executed).isFalse();
        verify(historyRepository).save(argThat(h ->
                h.getResult().equals("FAILURE")
                && h.getErrorDetail() != null));
    }

    @Test
    void tryExecuteBidAction_disablesRuleAndSkipsActionWhenDailyCapReached() {
        when(ruleService.countTodayActions(travelerId)).thenReturn(20L);

        boolean executed = executor.tryExecuteBidAction(rule, travelerId, bidId,
                "AUTO_ACCEPT", () -> { throw new AssertionError("action must not run"); });

        assertThat(executed).isFalse();
        assertThat(rule.isEnabled()).isFalse();
        verify(ruleRepository).save(rule);
        verify(historyRepository).save(argThat(h -> h.getResult().equals("CAP_REACHED")));
    }

    @Test
    void tryExecuteBidAction_dailyCapReached_logsRuleNameNotNullPresetRuleId() {
        // Pour une règle CUSTOM, presetRuleId est null (réservé aux presets) : le log au
        // plafond quotidien doit rester exploitable (nom de la règle), pas "rule null
        // disabled" (FIX 4). Cohérent avec writeHistory qui fait déjà ce fallback.
        rule.setPresetRuleId(null);
        rule.setRuleType("CUSTOM");
        rule.setName("Ma règle custom");
        when(ruleService.countTodayActions(travelerId)).thenReturn(20L);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AutomationActionExecutor.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            executor.tryExecuteBidAction(rule, travelerId, bidId, "CUSTOM_AUTO_ACCEPT", () -> null);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        boolean hasExploitableLog = appender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("disabled")
                        && e.getFormattedMessage().contains("Ma règle custom"));
        boolean hasNullRuleLog = appender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("rule null disabled"));

        assertThat(hasExploitableLog).isTrue();
        assertThat(hasNullRuleLog).isFalse();
    }

    @Test
    void recordNotification_writesSuccessHistory() {
        executor.recordNotification(rule, travelerId, "NOTIFY_LOW_MARGIN");

        verify(historyRepository).save(argThat(h ->
                h.getTravelerId().equals(travelerId)
                && h.getBidId() == null
                && h.getActionTaken().equals("NOTIFY_LOW_MARGIN")
                && h.getResult().equals("SUCCESS")));
    }
}
