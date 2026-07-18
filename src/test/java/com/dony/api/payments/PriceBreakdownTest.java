package com.dony.api.payments;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class PriceBreakdownTest {

    @Test void modelB_net35_rate12() {
        PriceBreakdown b = PriceBreakdown.fromNet(new BigDecimal("35"), new BigDecimal("0.12"));
        assertThat(b.net()).isEqualByComparingTo("35.00");
        assertThat(b.commission()).isEqualByComparingTo("4.20");
        assertThat(b.gross()).isEqualByComparingTo("39.20");
    }

    /** PriceBreakdown ne fait plus de conversion cents (voir MinorUnits/MinorUnitsTest) ;
     *  on vérifie ici l'invariant dont dépendent les appelants : net/commission/gross
     *  restent toujours scale 2 et cohérents (gross = net + commission), même avec un
     *  taux ayant beaucoup de décimales (ex. 12.333...%). */
    @Test void modelB_fractionalRate_staysScale2AndConsistent() {
        PriceBreakdown b = PriceBreakdown.fromNet(new BigDecimal("35"), new BigDecimal("0.123456789"));
        assertThat(b.net().scale()).isEqualTo(2);
        assertThat(b.commission().scale()).isEqualTo(2);
        assertThat(b.gross().scale()).isEqualTo(2);
        assertThat(b.gross()).isEqualByComparingTo(b.net().add(b.commission()));
    }
}
