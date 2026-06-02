package com.dony.api.payments.cash;

/**
 * Canal par lequel la commission Dony a été prélevée pour un bid hors escrow
 * (CASH, WAVE, ORANGE_MONEY). Sert à router le remboursement vers le bon support
 * en cas d'annulation (crédit wallet si WALLET, refund Stripe si CARD).
 */
public enum CommissionChargedVia {
    WALLET,
    CARD
}
