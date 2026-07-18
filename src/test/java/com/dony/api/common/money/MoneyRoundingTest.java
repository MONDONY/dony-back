package com.dony.api.common.money;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MoneyRoundingTest {

    @Test void nearestMultipleOf5Down() { assertThat(MoneyRounding.roundTransactionalMinor(7871, 5)).isEqualTo(7870); }
    @Test void nearestMultipleOf5Up()   { assertThat(MoneyRounding.roundTransactionalMinor(7873, 5)).isEqualTo(7875); }
    @Test void exactMultipleUnchanged() { assertThat(MoneyRounding.roundTransactionalMinor(7870, 5)).isEqualTo(7870); }
    @Test void increment1IsIdentity()   { assertThat(MoneyRounding.roundTransactionalMinor(7871, 1)).isEqualTo(7871); }

    /** Plancher (spec §5.6 règle 3) : un dû > 0 ne s'arrondit jamais à 0. */
    @Test void positiveDueNeverRoundsToZero() { assertThat(MoneyRounding.roundTransactionalMinor(2, 5)).isEqualTo(5); }
    @Test void zeroStaysZero()                { assertThat(MoneyRounding.roundTransactionalMinor(0, 5)).isZero(); }

    /** Remboursement (spec §5.6 règle 4) : incrément supérieur, faveur utilisateur. */
    @Test void refundRoundsUp()      { assertThat(MoneyRounding.roundRefundMinor(7871, 5)).isEqualTo(7875); }
    @Test void refundExactUnchanged(){ assertThat(MoneyRounding.roundRefundMinor(7870, 5)).isEqualTo(7870); }
}
