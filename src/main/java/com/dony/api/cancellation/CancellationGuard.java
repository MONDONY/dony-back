package com.dony.api.cancellation;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidStatus;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;

/**
 * Verrou d'annulation (D3) partagé entre {@code BidService.cancelBid} et
 * {@code CancellationService.cancelAfterHandover}.
 *
 * <p>Règle : plus d'annulation une fois le colis en transit, ni une fois le départ
 * réel atteint pour un colis déjà remis (backstop si le scan TRANSIT n'a jamais eu
 * lieu). Le scan TRANSIT est ainsi découplé du droit d'annuler.
 */
public final class CancellationGuard {

    private CancellationGuard() {
    }

    public static void assertCancellable(BidEntity bid, AnnouncementEntity announcement) {
        if (bid.getStatus() == BidStatus.IN_TRANSIT) {
            throw locked();
        }
        if (bid.getStatus() == BidStatus.HANDED_OVER
                && announcement != null
                && announcement.getDepartureAt() != null
                && !OffsetDateTime.now().isBefore(announcement.getDepartureAt())) {
            throw locked();
        }
    }

    private static DonyBusinessException locked() {
        return new DonyBusinessException(
                HttpStatus.CONFLICT,
                "cancel-locked",
                "Cancellation locked",
                "Le colis est en transit (ou le départ est dépassé) : annulation impossible.");
    }
}
