package com.dony.api.favorites;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tous les jours à minuit : supprime physiquement les favoris dont la cible
 * (trajet ou demande d'envoi) a atteint un état terminal — évite l'accumulation
 * indéfinie de lignes sur des cibles qui ne sont plus jamais actionnables
 * (cf. FavoriteRepositoryTest pour la requête elle-même).
 */
@ExtendWith(MockitoExtension.class)
class FavoriteCleanupSchedulerTest {

    @Mock private FavoriteRepository favoriteRepository;
    @InjectMocks private FavoriteCleanupScheduler scheduler;

    @Test
    void purgeTerminalFavorites_delegatesToBothRepositoryQueries() {
        when(favoriteRepository.deleteTripFavoritesForTerminalAnnouncements()).thenReturn(3);
        when(favoriteRepository.deletePackageRequestFavoritesForTerminalRequests()).thenReturn(2);

        scheduler.purgeTerminalFavorites();

        verify(favoriteRepository).deleteTripFavoritesForTerminalAnnouncements();
        verify(favoriteRepository).deletePackageRequestFavoritesForTerminalRequests();
    }

    @Test
    void purgeTerminalFavorites_noneToClean_doesNotThrow() {
        when(favoriteRepository.deleteTripFavoritesForTerminalAnnouncements()).thenReturn(0);
        when(favoriteRepository.deletePackageRequestFavoritesForTerminalRequests()).thenReturn(0);

        scheduler.purgeTerminalFavorites();

        verify(favoriteRepository).deleteTripFavoritesForTerminalAnnouncements();
        verify(favoriteRepository).deletePackageRequestFavoritesForTerminalRequests();
    }
}
