package com.yadony.api.payments.cash;

public enum PaymentMethod {
    STRIPE,
    CASH,
    WAVE,
    ORANGE_MONEY;

    /**
     * Modes où l'expéditeur doit payer via un lien externe après l'acceptation du bid, et
     * reçoit donc une notification de paiement dédiée portant ce lien.
     *
     * <p>Source unique de la liste : elle décide à la fois de l'initiation du paiement
     * ({@code MobileMoneyBidAcceptedListener}) et de la suppression du push générique
     * « Demande acceptée », qui ferait doublon sur la même action.
     */
    public boolean isMobileMoney() {
        return this == WAVE || this == ORANGE_MONEY;
    }
}
