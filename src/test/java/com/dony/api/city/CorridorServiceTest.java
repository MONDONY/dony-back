package com.dony.api.city;

import com.dony.api.city.dto.PopularCorridorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CorridorServiceTest {

    @Mock
    private CorridorRepository corridorRepository;

    private CorridorService corridorService;

    @BeforeEach
    void setUp() {
        corridorService = new CorridorService(corridorRepository);
    }

    @Test
    void getPopular_returnsMappedCorridors() {
        CorridorEntity entity = makeEntity("Paris", "France", "Dakar", "Sénégal", 10);
        when(corridorRepository.findTopByUsageCount(6)).thenReturn(List.of(entity));

        List<PopularCorridorResponse> result = corridorService.getPopular(6);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).departureCity()).isEqualTo("Paris");
        assertThat(result.get(0).arrivalCity()).isEqualTo("Dakar");
        assertThat(result.get(0).departureCountry()).isEqualTo("France");
        assertThat(result.get(0).arrivalCountry()).isEqualTo("Sénégal");
    }

    @Test
    void getPopular_capsLimitAt20() {
        when(corridorRepository.findTopByUsageCount(20)).thenReturn(List.of());
        corridorService.getPopular(100);
        verify(corridorRepository).findTopByUsageCount(20);
    }

    @Test
    void upsertCorridor_insertsNewCorridor() {
        when(corridorRepository.existsByDepartureCityAndArrivalCity("Lyon", "Bamako"))
            .thenReturn(false);
        when(corridorRepository.save(any(CorridorEntity.class))).thenReturn(new CorridorEntity());

        corridorService.upsertCorridor("Lyon", "France", "Bamako", "Mali");

        verify(corridorRepository).save(any(CorridorEntity.class));
        verify(corridorRepository, never()).incrementUsageCount(any(), any());
    }

    @Test
    void upsertCorridor_incrementsExistingCorridor() {
        when(corridorRepository.existsByDepartureCityAndArrivalCity("Paris", "Dakar"))
            .thenReturn(true);

        corridorService.upsertCorridor("Paris", "France", "Dakar", "Sénégal");

        verify(corridorRepository).incrementUsageCount("Paris", "Dakar");
        verify(corridorRepository, never()).save(any());
    }

    private CorridorEntity makeEntity(String dep, String depCountry, String arr,
                                      String arrCountry, int count) {
        CorridorEntity e = new CorridorEntity();
        e.setId(UUID.randomUUID());
        e.setDepartureCity(dep);
        e.setDepartureCountry(depCountry);
        e.setArrivalCity(arr);
        e.setArrivalCountry(arrCountry);
        e.setUsageCount(count);
        e.setLastUsedAt(OffsetDateTime.now());
        e.setCreatedAt(OffsetDateTime.now());
        return e;
    }
}
