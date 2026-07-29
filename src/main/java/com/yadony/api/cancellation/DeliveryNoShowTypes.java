package com.yadony.api.cancellation;

/**
 * Source de vérité unique pour le mapping reason → type de litige du flux
 * "no-show à la livraison" (scope DELIVERY). Utilisée à la fois par le chemin
 * contesté ({@link CancellationService#contestDeliveryNoShow}) et non contesté
 * ({@link DeliveryNoShowUncontestedScheduler#openUncontestedDispute}) pour
 * éviter deux mappings dupliqués (et deux jeux de constantes) qui pourraient
 * diverger silencieusement.
 */
public final class DeliveryNoShowTypes {

    public static final String REASON_RECIPIENT_NO_SHOW = "RECIPIENT_NO_SHOW";
    public static final String REASON_TRAVELER_DELIVERY_NO_SHOW = "TRAVELER_DELIVERY_NO_SHOW";

    private DeliveryNoShowTypes() {
    }

    public static boolean isRecipientNoShow(String reason) {
        return REASON_RECIPIENT_NO_SHOW.equals(reason);
    }

    /** RECIPIENT_NO_SHOW → contesté par le sender → RECIPIENT_NO_SHOW_CONTESTED ;
     *  TRAVELER_DELIVERY_NO_SHOW → contesté par le traveler → TRAVELER_DELIVERY_NO_SHOW_CONTESTED. */
    public static String contestedDisputeType(String reason) {
        return isRecipientNoShow(reason) ? "RECIPIENT_NO_SHOW_CONTESTED" : "TRAVELER_DELIVERY_NO_SHOW_CONTESTED";
    }

    /** Variante non contestée (litige ouvert directement par expiration du délai). */
    public static String uncontestedDisputeType(String reason) {
        return isRecipientNoShow(reason) ? REASON_RECIPIENT_NO_SHOW : REASON_TRAVELER_DELIVERY_NO_SHOW;
    }
}
