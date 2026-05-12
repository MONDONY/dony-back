package com.dony.api.addressbook.recipient;

import com.dony.api.addressbook.recipient.dto.CreateRecipientRequest;
import com.dony.api.addressbook.recipient.dto.RecipientDto;
import com.dony.api.addressbook.recipient.dto.UpdateRecipientRequest;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientServiceTest {

    @Mock
    private RecipientRepository repository;

    @Mock
    private AuditService auditService;

    private RecipientService service;

    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new RecipientService(repository, auditService);
        userId = UUID.randomUUID();
    }

    private RecipientEntity buildEntity(UUID userId) {
        RecipientEntity e = new RecipientEntity();
        e.setUserId(userId);
        e.setFullName("Mamadou Diallo");
        e.setPhoneE164("+221701234567");
        e.setCity("Dakar");
        e.setCountry("SN");
        return e;
    }

    @Test
    void findAll_returnsRecipientsForUser() {
        RecipientEntity e1 = buildEntity(userId);
        RecipientEntity e2 = buildEntity(userId);
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(e1, e2));

        List<RecipientDto> result = service.findAll(userId);

        assertThat(result).hasSize(2);
    }

    @Test
    void create_persistsAndReturnsDto() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateRecipientRequest request = new CreateRecipientRequest(
                "Fatou Diop", "Mère", "+221701234567", null,
                "Rue 12", "Dakar", "SN", null);

        RecipientDto result = service.create(userId, request);

        assertThat(result.fullName()).isEqualTo("Fatou Diop");
        assertThat(result.country()).isEqualTo("SN");
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void update_withValidOwnership_updatesFields() {
        UUID id = UUID.randomUUID();
        RecipientEntity entity = buildEntity(userId);

        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRecipientRequest request = new UpdateRecipientRequest(
                "Aminata Sow", "Soeur", "+2250101234567", null,
                null, "Abidjan", "CI", "Quartier Plateau");

        RecipientDto result = service.update(userId, id, request);

        assertThat(result.fullName()).isEqualTo("Aminata Sow");
        assertThat(result.country()).isEqualTo("CI");
        assertThat(result.notes()).isEqualTo("Quartier Plateau");
    }

    @Test
    void update_withWrongUserId_throwsNotFoundException() {
        UUID otherId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(otherId, id)).thenReturn(Optional.empty());

        UpdateRecipientRequest request = new UpdateRecipientRequest(
                "X", null, "+221701234567", null, null, "Dakar", "SN", null);

        assertThatThrownBy(() -> service.update(otherId, id, request))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void delete_softDeletesAndLogsAudit() {
        UUID id = UUID.randomUUID();
        RecipientEntity entity = buildEntity(userId);

        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.delete(userId, id);

        assertThat(entity.getDeletedAt()).isNotNull();
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void delete_withWrongUserId_throwsNotFoundException() {
        UUID otherId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(otherId, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(otherId, id))
                .isInstanceOf(DonyNotFoundException.class);

        verify(repository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }
}
