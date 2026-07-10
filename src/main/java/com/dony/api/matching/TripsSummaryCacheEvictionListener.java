package com.dony.api.matching;

import com.dony.api.payments.events.PaymentReleasedEvent;
import com.dony.api.tracking.events.DeliveryConfirmedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Invalide le cache {@code trips-summary} d'un voyageur dès qu'un de ses
 * indicateurs mensuels change :
 * <ul>
 *   <li>{@link DeliveryConfirmedEvent} — un colis livré fait passer un bid en
 *       {@code COMPLETED} → « kg vendus ce mois » évolue ;</li>
 *   <li>{@link PaymentReleasedEvent} — l'escrow libéré → « revenus » évolue.</li>
 * </ul>
 *
 * <p>Sans cette invalidation, le résumé (mis en cache par
 * {@link TripsSummaryService#computeSummary}) resterait figé jusqu'au TTL
 * Caffeine (5 min) — c'est la cause du « le kg vendu / le revenu du mois ne se
 * met pas à jour ».
 *
 * <p>Communication inter-packages par Spring Events (règle d'archi : pas
 * d'injection directe de service cross-package). {@code AFTER_COMMIT} garantit
 * que l'écriture (bid COMPLETED / paiement RELEASED) est committée avant
 * l'éviction, donc le prochain recalcul lit l'état à jour.
 */
@Component
public class TripsSummaryCacheEvictionListener {

    private final TripsSummaryService tripsSummaryService;

    public TripsSummaryCacheEvictionListener(TripsSummaryService tripsSummaryService) {
        this.tripsSummaryService = tripsSummaryService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        tripsSummaryService.evictSummary(event.getTravelerId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentReleased(PaymentReleasedEvent event) {
        tripsSummaryService.evictSummary(event.getTravelerId());
    }
}
