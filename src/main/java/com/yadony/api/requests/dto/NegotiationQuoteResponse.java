package com.yadony.api.requests.dto;

import java.math.BigDecimal;

/**
 * Devis transparent pour l'expéditeur au moment d'accepter/payer un thread de
 * négociation : net voyageur, taux et montant de commission Yadony (toujours
 * au taux de base, non affecté par le promo — cf. {@code BidQuoteResponse}),
 * total réellement payé (promo déjà déduit), et statut du code promo.
 */
public record NegotiationQuoteResponse(
    BigDecimal netEur,
    BigDecimal rate,
    BigDecimal commissionEur,
    BigDecimal totalEur,
    boolean promoApplied,
    String promoLabel
) {}
