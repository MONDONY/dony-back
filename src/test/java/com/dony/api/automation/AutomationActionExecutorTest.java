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
    void recordNotification_writesSuccessHistory() {
        executor.recordNotification(rule, travelerId, "NOTIFY_LOW_MARGIN");

        verify(historyRepository).save(argThat(h ->
                h.getTravelerId().equals(travelerId)
                && h.getBidId() == null
                && h.getActionTaken().equals("NOTIFY_LOW_MARGIN")
                && h.getResult().equals("SUCCESS")));
    }
}
