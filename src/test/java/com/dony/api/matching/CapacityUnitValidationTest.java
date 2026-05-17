package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class CapacityUnitValidationTest {

    @Autowired
    private AnnouncementRepository repository;

    @Test
    void defaultCapacityUnit_isSuitcase23Kg() {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(java.util.UUID.randomUUID());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDepartureDate(LocalDate.now().plusDays(10));
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("10 rue de la Paix, Paris");
        e.setPickupLat(new BigDecimal("48.869419"));
        e.setPickupLng(new BigDecimal("2.330526"));
        e.setDeliveryAddressLabel("Plateau, Dakar");
        e.setDeliveryLat(new BigDecimal("14.693425"));
        e.setDeliveryLng(new BigDecimal("-17.447938"));
        e.setAvailableKg(new BigDecimal("23"));
        e.setTotalKg(new BigDecimal("23"));
        e.setPricePerKg(new BigDecimal("10"));
        e.setStatus(AnnouncementStatus.ACTIVE);
        // capacityUnit NOT set — should default to SUITCASE_23KG

        AnnouncementEntity saved = repository.save(e);

        assertThat(saved.getCapacityUnit()).isEqualTo(CapacityUnit.SUITCASE_23KG);
    }

    @Test
    void canPersistKgFreeCapacityUnit() {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(java.util.UUID.randomUUID());
        e.setDepartureCity("Lyon");
        e.setArrivalCity("Abidjan");
        e.setDepartureDate(LocalDate.now().plusDays(5));
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Part-Dieu, Lyon");
        e.setPickupLat(new BigDecimal("45.760600"));
        e.setPickupLng(new BigDecimal("4.859200"));
        e.setDeliveryAddressLabel("Plateau, Abidjan");
        e.setDeliveryLat(new BigDecimal("5.352900"));
        e.setDeliveryLng(new BigDecimal("-4.001200"));
        e.setAvailableKg(new BigDecimal("15"));
        e.setTotalKg(new BigDecimal("15"));
        e.setPricePerKg(new BigDecimal("8"));
        e.setStatus(AnnouncementStatus.ACTIVE);
        e.setCapacityUnit(CapacityUnit.KG_FREE);

        AnnouncementEntity saved = repository.save(e);

        assertThat(saved.getCapacityUnit()).isEqualTo(CapacityUnit.KG_FREE);
    }

    @Test
    void canPersistSuitcase32KgCapacityUnit() {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(java.util.UUID.randomUUID());
        e.setDepartureCity("Marseille");
        e.setArrivalCity("Dakar");
        e.setDepartureDate(LocalDate.now().plusDays(7));
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Gare Saint-Charles, Marseille");
        e.setPickupLat(new BigDecimal("43.303800"));
        e.setPickupLng(new BigDecimal("5.380200"));
        e.setDeliveryAddressLabel("Plateau, Dakar");
        e.setDeliveryLat(new BigDecimal("14.693425"));
        e.setDeliveryLng(new BigDecimal("-17.447938"));
        e.setAvailableKg(new BigDecimal("32"));
        e.setTotalKg(new BigDecimal("32"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setStatus(AnnouncementStatus.ACTIVE);
        e.setCapacityUnit(CapacityUnit.SUITCASE_32KG);

        AnnouncementEntity saved = repository.save(e);

        assertThat(saved.getCapacityUnit()).isEqualTo(CapacityUnit.SUITCASE_32KG);
    }
}
