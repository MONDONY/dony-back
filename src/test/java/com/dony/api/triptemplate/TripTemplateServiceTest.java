package com.dony.api.triptemplate;

import com.dony.api.common.AuditService;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.triptemplate.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripTemplateServiceTest {

    @Mock TripTemplateRepository repository;
    @Mock AuditService auditService;
    @InjectMocks TripTemplateService service;

    private final UUID userId = UUID.randomUUID();

    private CreateTripTemplateRequest createRequest(List<String> categories) {
        return new CreateTripTemplateRequest(
                "Mon Paris->Dakar", "🇸🇳",
                "Paris", 48.85, 2.35,
                "Dakar", 14.71, -17.46,
                "PLANE", "SUITCASE_23KG", 23, 8.0, categories);
    }

    @Test
    void create_mapsFieldsAndJoinsCategories() {
        var dto = service.create(userId, createRequest(List.of("Vêtements", "Documents")));

        assertThat(dto.label()).isEqualTo("Mon Paris->Dakar");
        assertThat(dto.departureCity()).isEqualTo("Paris");
        assertThat(dto.arrivalCity()).isEqualTo("Dakar");
        assertThat(dto.pricePerKg()).isEqualTo(8.0);
        assertThat(dto.acceptedCategories()).containsExactly("Vêtements", "Documents");

        ArgumentCaptor<TripTemplateEntity> captor = ArgumentCaptor.forClass(TripTemplateEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getAcceptedCategories()).isEqualTo("Vêtements,Documents");
        verify(auditService).log(eq("TRIP_TEMPLATE"), any(), eq("TRIP_TEMPLATE_CREATED"), eq(userId), anyMap());
    }

    @Test
    void create_nullCategories_storesNullAndReturnsEmptyList() {
        var dto = service.create(userId, createRequest(null));
        assertThat(dto.acceptedCategories()).isEmpty();

        ArgumentCaptor<TripTemplateEntity> captor = ArgumentCaptor.forClass(TripTemplateEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAcceptedCategories()).isNull();
    }

    @Test
    void findAll_returnsMappedDtos() {
        TripTemplateEntity e = new TripTemplateEntity();
        e.setUserId(userId);
        e.setLabel("T1");
        e.setDepartureCity("Lyon");
        e.setArrivalCity("Abidjan");
        e.setTransportMode("PLANE");
        e.setCapacityUnit("SUITCASE_23KG");
        e.setAvailableKg(23);
        e.setPricePerKg(8.0);
        e.setAcceptedCategories("Vêtements,Cosmétiques");
        when(repository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(e));

        var result = service.findAll(userId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).label()).isEqualTo("T1");
        assertThat(result.get(0).acceptedCategories()).containsExactly("Vêtements", "Cosmétiques");
    }

    @Test
    void update_existing_updatesFields() {
        UUID id = UUID.randomUUID();
        TripTemplateEntity e = new TripTemplateEntity();
        e.setUserId(userId);
        e.setLabel("old");
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(e));

        var req = new UpdateTripTemplateRequest(
                "new label", null, "Marseille", null, null, "Dakar", null, null,
                "BOAT", "KG_FREE", 30, 9.0, List.of("Documents"));
        var dto = service.update(userId, id, req);

        assertThat(dto.label()).isEqualTo("new label");
        assertThat(dto.transportMode()).isEqualTo("BOAT");
        assertThat(dto.acceptedCategories()).containsExactly("Documents");
        verify(auditService).log(eq("TRIP_TEMPLATE"), any(), eq("TRIP_TEMPLATE_UPDATED"), eq(userId), anyMap());
    }

    @Test
    void update_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(userId, id,
                new UpdateTripTemplateRequest("x", null, "A", null, null, "B", null, null,
                        "PLANE", "SUITCASE_23KG", 23, 8.0, null)))
                .isInstanceOf(DonyNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void delete_existing_softDeletesAndAudits() {
        UUID id = UUID.randomUUID();
        TripTemplateEntity e = new TripTemplateEntity();
        e.setUserId(userId);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(e));

        service.delete(userId, id);

        assertThat(e.getDeletedAt()).isNotNull();
        verify(repository).save(e);
        verify(auditService).log(eq("TRIP_TEMPLATE"), any(), eq("TRIP_TEMPLATE_DELETED"), eq(userId), anyMap());
    }

    @Test
    void delete_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(userId, id))
                .isInstanceOf(DonyNotFoundException.class);
    }
}
