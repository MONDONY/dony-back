package com.dony.api.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

@ExtendWith(MockitoExtension.class)
class FirestoreServiceTest {

    @Test
    void createConversation_doesNotThrow_whenFirestoreIsNull() {
        var service = new FirestoreService(null);
        service.createConversation("conv_test", Map.of("key", "val"));
    }

    @Test
    void addSystemMessage_doesNotThrow_whenFirestoreIsNull() {
        var service = new FirestoreService(null);
        service.addSystemMessage("conv_test", "Hello system");
    }

    @Test
    void updateLastMessage_doesNotThrow_whenFirestoreIsNull() {
        var service = new FirestoreService(null);
        service.updateLastMessage("conv_test", "preview", "2026-04-29T10:00:00Z");
    }

    @Test
    void softDeleteMessage_doesNotThrow_whenFirestoreIsNull() {
        var service = new FirestoreService(null);
        service.softDeleteMessage("conv_test", "msg_001");
    }
}
