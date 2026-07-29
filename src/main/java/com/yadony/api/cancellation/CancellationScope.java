package com.yadony.api.cancellation;

/** HANDOVER = remise expéditeur→voyageur au départ (existant).
 *  DELIVERY = remise voyageur→destinataire à l'arrivée (nouveau). */
public enum CancellationScope {
    HANDOVER,
    DELIVERY
}
