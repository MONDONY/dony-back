package com.dony.api.payments;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Modèle B : prix saisi = NET voyageur ; l'expéditeur paie gross = net*(1+rate) ; commission = gross-net. */
public record PriceBreakdown(BigDecimal net, BigDecimal commission, BigDecimal gross) {

    public static PriceBreakdown fromNet(BigDecimal net, BigDecimal rate) {
        BigDecimal n = net.setScale(2, RoundingMode.HALF_UP);
        BigDecimal commission = n.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross = n.add(commission);
        return new PriceBreakdown(n, commission, gross);
    }

    public long grossCents()      { return gross.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(); }
    public long commissionCents() { return commission.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(); }
}
