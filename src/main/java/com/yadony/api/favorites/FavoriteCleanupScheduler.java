package com.yadony.api.favorites;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tous les jours à minuit : supprime physiquement les favoris (TRIP et
 * PACKAGE_REQUEST) dont la cible a atteint un état terminal
 * (COMPLETED/CANCELLED/EXPIRED selon le type). Sans ce nettoyage, la table
 * favorites accumule indéfiniment des lignes sur des trajets/demandes qui ne
 * sont plus jamais actionnables — le filtrage à la lecture
 * ({@code getFavoriteTrips}) masque le symptôme mais ne nettoie rien en base.
 */
@Component
public class FavoriteCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(FavoriteCleanupScheduler.class);

    private final FavoriteRepository favoriteRepository;

    public FavoriteCleanupScheduler(FavoriteRepository favoriteRepository) {
        this.favoriteRepository = favoriteRepository;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    @Transactional
    public void purgeTerminalFavorites() {
        int trips = favoriteRepository.deleteTripFavoritesForTerminalAnnouncements();
        int packageRequests = favoriteRepository.deletePackageRequestFavoritesForTerminalRequests();
        if (trips > 0 || packageRequests > 0) {
            log.info("FavoriteCleanup: {} favoris trajet + {} favoris demande d'envoi nettoyés (cible terminale)",
                    trips, packageRequests);
        }
    }
}
