package com.dony.api.addressbook.pickup;

import com.dony.api.addressbook.pickup.dto.CreatePickupAddressRequest;
import com.dony.api.addressbook.pickup.dto.PickupAddressDto;
import com.dony.api.addressbook.pickup.dto.UpdatePickupAddressRequest;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PickupAddressServiceTest {

    @Mock
    private PickupAddressRepository repository;

    @Mock
    private AuditService auditService;

    private PickupAddressService service;

    private UUID userId;

    @BeforeEach
    void setUp() {
        service = new PickupAddressService(repository, auditService);
        userId = UUID.randomUUID();
    }

    private PickupAddressEntity buildEntity(UUID userId, boolean isDefault) {
        PickupAddressEntity entity = new PickupAddressEntity();
        entity.setUserId(userId);
        entity.setLabel("Maison");
        entity.setStreet("10 rue de la Paix");
        entity.setPostalCode("75001");
        entity.setCity("Paris");
        entity.setCountry("FR");
        entity.setDefault(isDefault);
        return entity;
    }

    @Test
    void findAll_returnsAddressesForUser() {
        PickupAddressEntity e1 = buildEntity(userId, true);
        PickupAddressEntity e2 = buildEntity(userId, false);
        when(repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId)).thenReturn(List.of(e1, e2));

        List<PickupAddressDto> result = service.findAll(userId);

        assertThat(result).hasSize(2);
        verify(repository).findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId);
    }

    @Test
    void create_withIsDefault_true_unsetsOtherDefaults() {
        PickupAddressEntity existingDefault = buildEntity(userId, true);
        when(repository.findDefaultByUserId(userId)).thenReturn(Optional.of(existingDefault));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreatePickupAddressRequest request = new CreatePickupAddressRequest(
                "Bureau", "5 avenue des Champs", "75008", "Paris", "FR",
                null, null, null, null, true);

        PickupAddressDto result = service.create(userId, request);

        // existing default must have been unset
        assertThat(existingDefault.isDefault()).isFalse();
        assertThat(result.isDefault()).isTrue();
        assertThat(result.label()).isEqualTo("Bureau");
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void create_withIsDefault_false_doesNotUnsetOthers() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreatePickupAddressRequest request = new CreatePickupAddressRequest(
                "Bureau", "5 avenue des Champs", "75008", "Paris", "FR",
                null, null, null, null, false);

        service.create(userId, request);

        verify(repository, never()).findDefaultByUserId(any());
    }

    @Test
    void update_withWrongUserId_throwsNotFoundException() {
        UUID otherId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(otherId, id)).thenReturn(Optional.empty());

        UpdatePickupAddressRequest request = new UpdatePickupAddressRequest(
                "X", "X", "X", "X", "FR", null, null, null, null, false);

        assertThatThrownBy(() -> service.update(otherId, id, request))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void update_success_withIsDefault_false_updatesFields() {
        UUID id = UUID.randomUUID();
        PickupAddressEntity entity = buildEntity(userId, false);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdatePickupAddressRequest request = new UpdatePickupAddressRequest(
                "Nouveau label", "15 rue Neuve", "69001", "Lyon", "FR",
                null, null, null, null, false);

        PickupAddressDto result = service.update(userId, id, request);

        assertThat(result.label()).isEqualTo("Nouveau label");
        assertThat(result.city()).isEqualTo("Lyon");
        assertThat(result.isDefault()).isFalse();
        verify(repository, never()).findDefaultByUserId(any());
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void update_success_withIsDefault_true_andEntityNotDefault_unsetsOthers() {
        UUID id = UUID.randomUUID();
        PickupAddressEntity entity = buildEntity(userId, false);
        PickupAddressEntity existingDefault = buildEntity(userId, true);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));
        when(repository.findDefaultByUserId(userId)).thenReturn(Optional.of(existingDefault));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdatePickupAddressRequest request = new UpdatePickupAddressRequest(
                "Bureau", "5 av. des Champs", "75008", "Paris", "FR",
                null, null, null, null, true);

        PickupAddressDto result = service.update(userId, id, request);

        assertThat(existingDefault.isDefault()).isFalse();
        assertThat(result.isDefault()).isTrue();
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void update_success_withIsDefault_true_andEntityAlreadyDefault_skipsUnset() {
        UUID id = UUID.randomUUID();
        PickupAddressEntity entity = buildEntity(userId, true);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdatePickupAddressRequest request = new UpdatePickupAddressRequest(
                "Maison", "10 rue de la Paix", "75001", "Paris", "FR",
                null, null, null, null, true);

        PickupAddressDto result = service.update(userId, id, request);

        assertThat(result.isDefault()).isTrue();
        verify(repository, never()).findDefaultByUserId(any());
        verify(auditService).log(any(), any(), any(), any(), any());
    }

    @Test
    void setDefault_unsetsAllThenSetsTarget() {
        UUID id = UUID.randomUUID();
        PickupAddressEntity target = buildEntity(userId, false);
        PickupAddressEntity existingDefault = buildEntity(userId, true);

        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(target));
        when(repository.findDefaultByUserId(userId)).thenReturn(Optional.of(existingDefault));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PickupAddressDto result = service.setDefault(userId, id);

        assertThat(existingDefault.isDefault()).isFalse();
        assertThat(target.isDefault()).isTrue();
        assertThat(result.isDefault()).isTrue();
    }

    @Test
    void setDefault_withUnknownId_throwsNotFoundException() {
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setDefault(userId, id))
                .isInstanceOf(DonyNotFoundException.class);
    }

    @Test
    void delete_softDeletesAndLogsAudit() {
        UUID id = UUID.randomUUID();
        PickupAddressEntity entity = buildEntity(userId, false);

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
