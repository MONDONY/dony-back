package com.dony.api.messaging;

import com.dony.api.messaging.dto.NotifyMessageRequest;
import com.dony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessagingNotifyControllerTest {

    @Mock ConversationRepository conversationRepository;
    @Mock NotificationDispatcher notificationDispatcher;

    MessagingNotifyController controller;

    @BeforeEach
    void setUp() {
        controller = new MessagingNotifyController(conversationRepository, notificationDispatcher);
        ReflectionTestUtils.setField(controller, "internalSecret", "test-secret");
    }

    @Test
    void notify_returns401_whenSecretMissing() {
        var req = new NotifyMessageRequest("conv_1", "uid-1", "hello");
        assertThatThrownBy(() -> controller.notify(null, req))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                var e = (ResponseStatusException) ex;
                assert e.getStatusCode().value() == 401;
            });
    }

    @Test
    void notify_returns401_whenSecretWrong() {
        var req = new NotifyMessageRequest("conv_1", "uid-1", "hello");
        assertThatThrownBy(() -> controller.notify("wrong-secret", req))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> {
                var e = (ResponseStatusException) ex;
                assert e.getStatusCode().value() == 401;
            });
    }

    @Test
    void notify_dispatchesNotification_whenValidSecret() {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        var conv = new ConversationEntity(UUID.randomUUID(), senderId, travelerId, "conv_bid1");
        when(conversationRepository.findAll()).thenReturn(List.of(conv));

        controller.notify("test-secret",
            new NotifyMessageRequest("conv_bid1", "uid-sender", "Hello!"));

        verify(notificationDispatcher).sendMessageNotification(
            eq(senderId), eq(travelerId), eq("uid-sender"), eq("Hello!"), eq("conv_bid1"));
    }
}
