package com.dony.api.cancellation.events;

import java.util.UUID;

/**
 * Publié quand le voyageur saisit le bon code de retour : le colis est confirmé rendu
 * à l'expéditeur (annulation après remise). Écouté par la tranche D (lève le délai J+3)
 * et la tranche E (autorise la note expéditeur -> voyageur après retour).
 */
public record ParcelReturnedEvent(UUID bidId, UUID travelerId, UUID senderId) {
}
