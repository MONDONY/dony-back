package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BidPhoneVisibilityTest {

    @Test
    void numeroRevele_siAccepteOuAuDela() {
        assertThat(BidService.phoneForStatus("0612345678", BidStatus.ACCEPTED)).isEqualTo("0612345678");
        assertThat(BidService.phoneForStatus("0612345678", BidStatus.HANDED_OVER)).isEqualTo("0612345678");
        assertThat(BidService.phoneForStatus("0612345678", BidStatus.IN_TRANSIT)).isEqualTo("0612345678");
        assertThat(BidService.phoneForStatus("0612345678", BidStatus.COMPLETED)).isEqualTo("0612345678");
    }

    @Test
    void numeroMasque_siAvantAcceptation() {
        assertThat(BidService.phoneForStatus("0612345678", BidStatus.PENDING)).isNull();
        assertThat(BidService.phoneForStatus("0612345678", BidStatus.PAYMENT_ESCROWED)).isNull();
        assertThat(BidService.phoneForStatus("0612345678", BidStatus.AWAITING_PAYMENT)).isNull();
        assertThat(BidService.phoneForStatus("0612345678", BidStatus.REJECTED)).isNull();
    }

    @Test
    void numeroNull_resteNull() {
        assertThat(BidService.phoneForStatus(null, BidStatus.ACCEPTED)).isNull();
    }
}
