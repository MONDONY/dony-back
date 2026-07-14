package com.dony.api.favorites;

import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.AnnouncementStatus;
import com.dony.api.matching.TransportMode;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.repository.PackageRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * atteint un état terminal : la ligne doit être supprimée physiquement, jamais
 * laissée en base (cf. FavoriteCleanupScheduler).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class FavoriteRepositoryTest {

    @Autowired FavoriteRepository favoriteRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired PackageRequestRepository packageRequestRepository;
    @PersistenceContext EntityManager entityManager;

    /** Compte les lignes en base sans passer par le @Where de l'entité. */
    private long countRowsInTable(UUID targetId) {
        return ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM favorites WHERE target_id = :tid")
                .setParameter("tid", targetId)
                .getSingleResult()).longValue();
    }

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
    @DisplayName("deleteTripFavoritesForTerminalAnnouncements : COMPLETED/CANCELLED supprimés physiquement, ACTIVE conservé")
    void deleteTripFavorites_onlyTerminalAnnouncements() {
        UUID userId = UUID.randomUUID();
        AnnouncementEntity completed = newAnnouncement(AnnouncementStatus.COMPLETED);
        AnnouncementEntity cancelled = newAnnouncement(AnnouncementStatus.CANCELLED);
        AnnouncementEntity active = newAnnouncement(AnnouncementStatus.ACTIVE);

        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.TRIP, completed.getId()));
        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.TRIP, cancelled.getId()));
        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.TRIP, active.getId()));

        int deleted = favoriteRepository.deleteTripFavoritesForTerminalAnnouncements();

        assertThat(deleted).isEqualTo(2);
        List<UUID> remaining = favoriteRepository.findTargetIds(userId, FavoriteTargetType.TRIP);
        assertThat(remaining).containsExactly(active.getId());
        // suppression physique : plus aucune ligne en base, même hors @Where
        assertThat(countRowsInTable(completed.getId())).isZero();
        assertThat(countRowsInTable(cancelled.getId())).isZero();
        assertThat(countRowsInTable(active.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("deleteTripFavoritesForTerminalAnnouncements : idempotent (rien à nettoyer → 0)")
    void deleteTripFavorites_idempotent() {
        favoriteRepository.deleteTripFavoritesForTerminalAnnouncements();
        int secondRun = favoriteRepository.deleteTripFavoritesForTerminalAnnouncements();
        assertThat(secondRun).isEqualTo(0);
    }

    @Test
    @DisplayName("deletePackageRequestFavoritesForTerminalRequests : COMPLETED/CANCELLED/EXPIRED supprimés, OPEN conservé")
    void deletePackageRequestFavorites_onlyTerminalRequests() {
        UUID userId = UUID.randomUUID();
        PackageRequestEntity completed = newPackageRequest(PackageRequestStatus.COMPLETED);
        PackageRequestEntity cancelled = newPackageRequest(PackageRequestStatus.CANCELLED);
        PackageRequestEntity expired = newPackageRequest(PackageRequestStatus.EXPIRED);
        PackageRequestEntity open = newPackageRequest(PackageRequestStatus.OPEN);

        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.PACKAGE_REQUEST, completed.getId()));
        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.PACKAGE_REQUEST, cancelled.getId()));
        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.PACKAGE_REQUEST, expired.getId()));
        favoriteRepository.saveAndFlush(new FavoriteEntity(userId, FavoriteTargetType.PACKAGE_REQUEST, open.getId()));

        int deleted = favoriteRepository.deletePackageRequestFavoritesForTerminalRequests();

        assertThat(deleted).isEqualTo(3);
        List<UUID> remaining = favoriteRepository.findTargetIds(userId, FavoriteTargetType.PACKAGE_REQUEST);
        assertThat(remaining).containsExactly(open.getId());
        assertThat(countRowsInTable(completed.getId())).isZero();
        assertThat(countRowsInTable(expired.getId())).isZero();
        assertThat(countRowsInTable(open.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("delete du repository = suppression physique (retrait manuel d'un favori)")
    void deleteFavorite_removesRowPhysically() {
        UUID userId = UUID.randomUUID();
        AnnouncementEntity active = newAnnouncement(AnnouncementStatus.ACTIVE);
        FavoriteEntity fav = favoriteRepository.saveAndFlush(
                new FavoriteEntity(userId, FavoriteTargetType.TRIP, active.getId()));

        favoriteRepository.delete(fav);
        favoriteRepository.flush();

        assertThat(countRowsInTable(active.getId())).isZero();
    }
}
