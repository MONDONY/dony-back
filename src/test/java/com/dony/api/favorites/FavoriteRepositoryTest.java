package com.dony.api.favorites;

import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.TransportMode;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie le nettoyage des favoris dont la cible (trajet ou demande d'envoi) a
 * atteint un état terminal : la ligne doit être soft-deleted, jamais laissée
 * active indéfiniment (cf. FavoriteCleanupScheduler).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class FavoriteRepositoryTest {

    @Autowired FavoriteRepository favoriteRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired PackageRequestRepository packageRequestRepository;

    private AnnouncementEntity newAnnouncement(AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(LocalDate.now().plusDays(5));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        a.setDeliveryLat(new BigDecimal("12.533579"));
        a.setDeliveryLng(new BigDecimal("-7.948969"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(status);
        return announcementRepository.saveAndFlush(a);
    }

    private PackageRequestEntity newPackageRequest(PackageRequestStatus status) {
        PackageRequestEntity pr = new PackageRequestEntity();
        pr.setSenderId(UUID.randomUUID());
        pr.setDepartureCity("Lyon");
        pr.setArrivalCity("Abidjan");
        pr.setDesiredDate(LocalDate.now().plusDays(5));
        pr.setDateToleranceDays((short) 2);
        pr.setWeightKg(new BigDecimal("5.00"));
        pr.setParcelSize(com.dony.api.requests.entity.ParcelSize.MEDIUM);
        pr.setContentCategory("Vêtements");
        pr.setTransportMode(TransportMode.PLANE);
        pr.setStatus(status);
        return packageRequestRepository.saveAndFlush(pr);
    }

    @Test
    @DisplayName("softDeleteTripFavoritesForTerminalAnnouncements : COMPLETED/CANCELLED nettoyés, ACTIVE conservé")
    void softDeleteTripFavorites_onlyTerminalAnnouncements() {
        UUID userId = UUID.randomUUID();
        AnnouncementEntity completed = newAnnouncement(AnnouncementStatus.COMPLETED);
        AnnouncementEntity cancelled = newAnnouncement(AnnouncementStatus.CANCELLED);
        AnnouncementEntity active = newAnnouncement(AnnouncementStatus.ACTIVE);

        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.TRIP, completed.getId()));
        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.TRIP, cancelled.getId()));
        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.TRIP, active.getId()));

        int updated = favoriteRepository.softDeleteTripFavoritesForTerminalAnnouncements();

        assertThat(updated).isEqualTo(2);
        List<UUID> remaining = favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP);
        assertThat(remaining).containsExactly(active.getId());
    }

    @Test
    @DisplayName("softDeleteTripFavoritesForTerminalAnnouncements : idempotent (rien à nettoyer → 0)")
    void softDeleteTripFavorites_idempotent() {
        favoriteRepository.softDeleteTripFavoritesForTerminalAnnouncements();
        int secondRun = favoriteRepository.softDeleteTripFavoritesForTerminalAnnouncements();
        assertThat(secondRun).isEqualTo(0);
    }

    @Test
    @DisplayName("softDeletePackageRequestFavoritesForTerminalRequests : COMPLETED/CANCELLED/EXPIRED nettoyés, OPEN conservé")
    void softDeletePackageRequestFavorites_onlyTerminalRequests() {
        UUID userId = UUID.randomUUID();
        PackageRequestEntity completed = newPackageRequest(PackageRequestStatus.COMPLETED);
        PackageRequestEntity cancelled = newPackageRequest(PackageRequestStatus.CANCELLED);
        PackageRequestEntity expired = newPackageRequest(PackageRequestStatus.EXPIRED);
        PackageRequestEntity open = newPackageRequest(PackageRequestStatus.OPEN);

        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.PACKAGE_REQUEST, completed.getId()));
        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.PACKAGE_REQUEST, cancelled.getId()));
        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.PACKAGE_REQUEST, expired.getId()));
        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.PACKAGE_REQUEST, open.getId()));

        int updated = favoriteRepository.softDeletePackageRequestFavoritesForTerminalRequests();

        assertThat(updated).isEqualTo(3);
        List<UUID> remaining = favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST);
        assertThat(remaining).containsExactly(open.getId());
    }

    @Test
    @DisplayName("un favori déjà soft-deleted n'est jamais recompté (idempotence stricte)")
    void alreadyDeletedFavorite_notCountedAgain() {
        UUID userId = UUID.randomUUID();
        AnnouncementEntity completed = newAnnouncement(AnnouncementStatus.COMPLETED);
        FavoriteEntity fav = favoriteRepository.saveAndFlush(
                new FavoriteEntity(userId, FavoriteTargetType.TRIP, completed.getId()));

        int firstRun = favoriteRepository.softDeleteTripFavoritesForTerminalAnnouncements();
        assertThat(firstRun).isEqualTo(1);

        int secondRun = favoriteRepository.softDeleteTripFavoritesForTerminalAnnouncements();
        assertThat(secondRun).isEqualTo(0);
    }
}
