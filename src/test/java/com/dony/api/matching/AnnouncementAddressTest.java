package com.dony.api.matching;

import com.dony.api.matching.dto.AddressDto;
import com.dony.api.matching.AnnouncementService;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidRepository;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.matching.dto.AnnouncementRequest;
import com.dony.api.matching.dto.AnnouncementResponse;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnnouncementAddressTest {

    private Validator validator;

    @BeforeEach
    void setup() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void addressDto_valid() {
        var dto = new AddressDto("12 rue Hugo, Lyon", 45.748, 4.846);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void addressDto_blankLabel_invalid() {
        var dto = new AddressDto("", 45.748, 4.846);
        var violations = validator.validate(dto);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("label"));
    }

    @Test
    void addressDto_latTooHigh_invalid() {
        var dto = new AddressDto("Paris", 91.0, 2.3);
        assertThat(validator.validate(dto)).anyMatch(v -> v.getPropertyPath().toString().equals("lat"));
    }

    @Test
    void addressDto_latTooLow_invalid() {
        var dto = new AddressDto("Paris", -91.0, 2.3);
        assertThat(validator.validate(dto)).anyMatch(v -> v.getPropertyPath().toString().equals("lat"));
    }

    @Test
    void addressDto_lngTooHigh_invalid() {
        var dto = new AddressDto("Dakar", 14.7, 181.0);
        assertThat(validator.validate(dto)).anyMatch(v -> v.getPropertyPath().toString().equals("lng"));
    }
}

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceAddressTest {

    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRepository bidRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks AnnouncementService announcementService;

    private static final String TRAVELER_UID = "firebase-uid-123";
    private static final java.util.UUID TRAVELER_ID = java.util.UUID.randomUUID();

    @Test
    void createAnnouncement_withValidAddresses_savesPickupAndDelivery() {
        var pickup = new AddressDto("12 rue Hugo, Lyon", 45.748, 4.846);
        var delivery = new AddressDto("Dakar Plateau, Sénégal", 14.693, -17.447);
        var request = buildRequest(pickup, delivery);
        var user = mockVerifiedTraveler();

        when(userRepository.findByFirebaseUid(TRAVELER_UID)).thenReturn(java.util.Optional.of(user));
        when(announcementRepository.save(any())).thenAnswer(inv -> {
            com.dony.api.matching.AnnouncementEntity e = inv.getArgument(0);
            return e;
        });
        when(bidRepository.countVisibleByAnnouncementId(any())).thenReturn(0L);

        AnnouncementResponse response = announcementService.createAnnouncement(TRAVELER_UID, request);

        assertThat(response.pickupAddress().label()).isEqualTo("12 rue Hugo, Lyon");
        assertThat(response.pickupAddress().lat()).isEqualTo(45.748);
        assertThat(response.deliveryAddress().label()).isEqualTo("Dakar Plateau, Sénégal");

        verify(announcementRepository).save(argThat(entity ->
            entity.getPickupAddressLabel().equals("12 rue Hugo, Lyon") &&
            entity.getPickupLat().doubleValue() == 45.748 &&
            entity.getDeliveryAddressLabel().equals("Dakar Plateau, Sénégal")
        ));
    }

    private AnnouncementRequest buildRequest(AddressDto pickup, AddressDto delivery) {
        return new AnnouncementRequest(
            "Paris", "Dakar",
            java.time.LocalDate.now().plusDays(10),
            null, null,
            pickup, delivery,
            java.math.BigDecimal.valueOf(10), java.math.BigDecimal.valueOf(5),
            com.dony.api.matching.TransportMode.PLANE,
            null, java.util.List.of(), java.util.List.of()
        );
    }

    private com.dony.api.auth.UserEntity mockVerifiedTraveler() {
        var user = new com.dony.api.auth.UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", TRAVELER_ID);
        user.setFirebaseUid(TRAVELER_UID);
        return user;
    }
}
