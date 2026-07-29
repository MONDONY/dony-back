package com.yadony.api.addressbook.recipient;

import com.yadony.api.auth.FinalizationReason;
import com.yadony.api.auth.events.UserFinalizedEvent;
import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecipientFinalizationListener")
class RecipientFinalizationListenerTest {

    @Mock private RecipientRepository recipientRepository;
    @Mock private AuditService auditService;

    @InjectMocks private RecipientFinalizationListener listener;

    private RecipientEntity recipient(UUID userId) {
        RecipientEntity e = new RecipientEntity();
        e.setUserId(userId);
        e.setFullName("Maman");
        e.setPhoneE164("+221771234567");
        e.setCity("Dakar");
        e.setCountry("SN");
        return e;
    }

    @Test
    @DisplayName("soft-delete tous les destinataires de l'utilisateur finalisé")
    void softDeletesAllRecipientsForFinalizedUser() {
        UUID userId = UUID.randomUUID();
        RecipientEntity r1 = recipient(userId);
        RecipientEntity r2 = recipient(userId);
        when(recipientRepository.findByUserIdOrderByUpdatedAtDesc(userId))
                .thenReturn(List.of(r1, r2));

        listener.onUserFinalized(new UserFinalizedEvent(userId, FinalizationReason.HARD_IMMEDIATE));

        assertThat(r1.getDeletedAt()).isNotNull();
        assertThat(r2.getDeletedAt()).isNotNull();
        verify(recipientRepository).saveAll(List.of(r1, r2));
    }

    @Test
    @DisplayName("écrit une entrée audit log RECIPIENT_GDPR_BULK_DELETE avec le compte exact")
    void writesAuditLogWithCount() {
        UUID userId = UUID.randomUUID();
        when(recipientRepository.findByUserIdOrderByUpdatedAtDesc(userId))
                .thenReturn(List.of(recipient(userId), recipient(userId), recipient(userId)));

        listener.onUserFinalized(new UserFinalizedEvent(userId, FinalizationReason.SOFT_GRACE_EXPIRED));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("RECIPIENT"), eq(userId), eq("RECIPIENT_GDPR_BULK_DELETE"),
                eq(userId), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("count", 3);
    }

    @Test
    @DisplayName("ne fait rien (ni save, ni audit log) quand l'utilisateur n'a aucun destinataire")
    void noOpWhenNoRecipients() {
        UUID userId = UUID.randomUUID();
        when(recipientRepository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());

        listener.onUserFinalized(new UserFinalizedEvent(userId, FinalizationReason.HARD_IMMEDIATE));

        verifyNoInteractions(auditService);
    }
}
