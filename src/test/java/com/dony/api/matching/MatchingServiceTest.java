package com.dony.api.matching;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.matching.dto.MatchingRequestDto;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private PackageRequestRepository packageRequestRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private MatchingService matchingService;

    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    private AnnouncementEntity activeAnnouncement;
    private UserEntity sender;

    @BeforeEach
    void setup() throws Exception {
        activeAnnouncement = new AnnouncementEntity();
        setField(activeAnnouncement, "id", ANNOUNCEMENT_ID);
        activeAnnouncement.setTravelerId(TRAVELER_ID);
        activeAnnouncement.setDepartureCity("Paris");
        activeAnnouncement.setArrivalCity("Dakar");
        activeAnnouncement.setDepartureDate(LocalDate.now().plusDays(10));
        activeAnnouncement.setAvailableKg(BigDecimal.valueOf(20));
        activeAnnouncement.setPricePerKg(BigDecimal.valueOf(5));
        activeAnnouncement.setStatus(AnnouncementStatus.ACTIVE);

        sender = new UserEntity();
        setField(sender, "id", SENDER_ID);
        sender.setFirstName("Marie");
        sender.setLastName("Dupont");
        setField(sender, "averageRating", BigDecimal.valueOf(4.5));
        setField(sender, "totalShipments", 8);
    }

    @Nested
    @DisplayName("findMatchingRequests")
    class FindMatchingRequests {

        @Test
        void returnsMatchingRequest_whenCorridorAndDateAndWeightMatch() throws Exception {
            PackageRequestEntity request = buildRequest(5, LocalDate.now().plusDays(10), 3);
            when(announcementRepository.findActiveByTravelerId(TRAVELER_ID))
                    .thenReturn(List.of(activeAnnouncement));
            when(packageRequestRepository.findOpenByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(request));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

            List<MatchingRequestDto> results = matchingService.findMatchingRequests(TRAVELER_ID);

            assertThat(results).hasSize(1);
            MatchingRequestDto dto = results.get(0);
            assertThat(dto.tripId()).isEqualTo(ANNOUNCEMENT_ID.toString());
            assertThat(dto.tripCorridor()).isEqualTo("Paris → Dakar");
            assertThat(dto.senderId()).isEqualTo(SENDER_ID.toString());
            assertThat(dto.senderName()).isEqualTo("Marie Dupont");
            assertThat(dto.senderInitials()).isEqualTo("MD");
            assertThat(dto.senderRating()).isEqualTo(4.5);
            assertThat(dto.weightKg()).isEqualTo(5.0);
        }

        @Test
        void excludesRequest_whenWeightExceedsAvailableKg() throws Exception {
            PackageRequestEntity request = buildRequest(25, LocalDate.now().plusDays(10), 3);
            when(announcementRepository.findActiveByTravelerId(TRAVELER_ID))
                    .thenReturn(List.of(activeAnnouncement));
            when(packageRequestRepository.findOpenByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(request));

            List<MatchingRequestDto> results = matchingService.findMatchingRequests(TRAVELER_ID);

            assertThat(results).isEmpty();
        }

        @Test
        void excludesRequest_whenDateOutsideTolerance() throws Exception {
            PackageRequestEntity request = buildRequest(5, LocalDate.now().plusDays(20), 3);
            when(announcementRepository.findActiveByTravelerId(TRAVELER_ID))
                    .thenReturn(List.of(activeAnnouncement));
            when(packageRequestRepository.findOpenByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(request));

            List<MatchingRequestDto> results = matchingService.findMatchingRequests(TRAVELER_ID);

            assertThat(results).isEmpty();
        }

        @Test
        void returnsEmpty_whenNoActiveAnnouncements() {
            when(announcementRepository.findActiveByTravelerId(TRAVELER_ID))
                    .thenReturn(List.of());

            List<MatchingRequestDto> results = matchingService.findMatchingRequests(TRAVELER_ID);

            assertThat(results).isEmpty();
        }

        @Test
        void excludesRequest_whenSenderNotFound() throws Exception {
            PackageRequestEntity request = buildRequest(5, LocalDate.now().plusDays(10), 3);
            when(announcementRepository.findActiveByTravelerId(TRAVELER_ID))
                    .thenReturn(List.of(activeAnnouncement));
            when(packageRequestRepository.findOpenByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(request));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

            List<MatchingRequestDto> results = matchingService.findMatchingRequests(TRAVELER_ID);

            assertThat(results).isEmpty();
        }

        @Test
        void sortsByMatchScoreDesc() throws Exception {
            PackageRequestEntity lowBudget = buildRequest(5, LocalDate.now().plusDays(10), 3);
            setField(lowBudget, "targetPriceEur", BigDecimal.valueOf(1)); // low budget → low score

            PackageRequestEntity goodBudget = buildRequest(5, LocalDate.now().plusDays(10), 3);
            setField(goodBudget, "targetPriceEur", BigDecimal.valueOf(100)); // good budget → high score

            UUID goodId = UUID.randomUUID();
            setField(goodBudget, "id", goodId);

            when(announcementRepository.findActiveByTravelerId(TRAVELER_ID))
                    .thenReturn(List.of(activeAnnouncement));
            when(packageRequestRepository.findOpenByCorridor(anyString(), anyString()))
                    .thenReturn(List.of(lowBudget, goodBudget));
            when(userRepository.findById(any())).thenReturn(Optional.of(sender));

            List<MatchingRequestDto> results = matchingService.findMatchingRequests(TRAVELER_ID);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).matchScore()).isGreaterThanOrEqualTo(results.get(1).matchScore());
        }

        @Test
        void handlesNullSenderFields_gracefully() throws Exception {
            UserEntity senderNoName = new UserEntity();
            setField(senderNoName, "id", SENDER_ID);
            setField(senderNoName, "totalShipments", 0);

            PackageRequestEntity request = buildRequest(5, LocalDate.now().plusDays(10), 3);
            when(announcementRepository.findActiveByTravelerId(TRAVELER_ID))
                    .thenReturn(List.of(activeAnnouncement));
            when(packageRequestRepository.findOpenByCorridor("Paris", "Dakar"))
                    .thenReturn(List.of(request));
            when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(senderNoName));

            List<MatchingRequestDto> results = matchingService.findMatchingRequests(TRAVELER_ID);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).senderName()).isEqualTo("Expéditeur");
            assertThat(results.get(0).senderRating()).isEqualTo(0.0);
        }
    }

    // ---- Helpers ----

    private PackageRequestEntity buildRequest(int weightKg, LocalDate desiredDate, int toleranceDays)
            throws Exception {
        PackageRequestEntity req = new PackageRequestEntity();
        setField(req, "id", REQUEST_ID);
        req.setSenderId(SENDER_ID);
        req.setDepartureCity("Paris");
        req.setArrivalCity("Dakar");
        req.setDesiredDate(desiredDate);
        req.setDateToleranceDays((short) toleranceDays);
        req.setWeightKg(BigDecimal.valueOf(weightKg));
        req.setContentCategory("Vêtements");
        req.setDescription("Colis de vêtements pour la famille");
        req.setTargetPriceEur(BigDecimal.valueOf(30));
        req.setStatus(PackageRequestStatus.OPEN);
        setField(req, "createdAt", LocalDateTime.now().minusDays(1));
        return req;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                var field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found on " + target.getClass());
    }
}
