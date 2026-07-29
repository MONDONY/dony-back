package com.yadony.api.disputes;

/**
 * Source de vérité unique pour reconnaître le litige "départ" historique
 * (scope HANDOVER) parmi les types de litige. Utilisée par
 * {@code AdminDisputesController.resolveLinkedCancellation} (package
 * {@code admin}) et {@link DisputeOpenedEventListener#handleDisputeOpened}
 * pour éviter que les deux endroits ne redéfinissent indépendamment ce
 * prédicat (import statique cross-package OK — seule l'injection de service
 * est interdite par CLAUDE.md).
 */
public final class DisputeTypes {

    public static final String SENDER_NO_SHOW_CONTESTED = "SENDER_NO_SHOW_CONTESTED";

    private DisputeTypes() {
    }

    public static boolean isHandover(String type) {
        return SENDER_NO_SHOW_CONTESTED.equals(type);
    }
}
