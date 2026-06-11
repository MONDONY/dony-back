package com.dony.api.matching;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class BidEntityTest {

    @Test
    void applyHandoverFrom_copiesWindowAndPickupLabel() {
        AnnouncementEntity ann = new AnnouncementEntity();
        LocalDateTime start = LocalDate.now().plusDays(5).atTime(16, 0);
        LocalDateTime end   = LocalDate.now().plusDays(5).atTime(18, 0);
        ann.setHandoverWindowStart(start);
        ann.setHandoverWindowEnd(end);
        ann.setPickupAddressLabel("Gare du Nord, Paris");
        ann.setPickupLat(BigDecimal.valueOf(48.88));
        ann.setPickupLng(BigDecimal.valueOf(2.35));

        BidEntity bid = new BidEntity();
        bid.applyHandoverFrom(ann);

        assertThat(bid.getHandoverWindowStart()).isEqualTo(start);
        assertThat(bid.getHandoverWindowEnd()).isEqualTo(end);
        assertThat(bid.getHandoverLocation()).isEqualTo("Gare du Nord, Paris");
    }

    @Test
    void applyHandoverFrom_legacyAnnouncementWithNullWindow_setsNulls() {
        AnnouncementEntity ann = new AnnouncementEntity();
        ann.setPickupAddressLabel("X");
        BidEntity bid = new BidEntity();
        bid.applyHandoverFrom(ann);
        assertThat(bid.getHandoverWindowStart()).isNull();
        assertThat(bid.getHandoverWindowEnd()).isNull();
        assertThat(bid.getHandoverLocation()).isEqualTo("X");
    }
}
