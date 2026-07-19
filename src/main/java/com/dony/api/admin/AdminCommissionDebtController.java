package com.dony.api.admin;

import com.dony.api.admin.dto.CommissionDebtResponse;
import com.dony.api.matching.AnnouncementEntity;
import com.dony.api.matching.AnnouncementRepository;
import com.dony.api.matching.BidEntity;
import com.dony.api.matching.BidRepository;
import com.dony.api.payments.cash.CashCommissionService;
import com.dony.api.payments.cash.CommissionStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Visibilité admin des créances de commission (spec §4.4) : bids dont le
 * prélèvement automatique de commission a échoué (ni wallet ni carte
 * disponible côté voyageur au moment du prélèvement) — {@code
 * commissionStatus = FAILED}. La stratégie de recouvrement effective reste au
 * spec PSP ; cet endpoint expose seulement la créance, datée et avec état.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminCommissionDebtController {

    private final BidRepository bidRepo;
    private final AnnouncementRepository announcementRepo;
    private final CashCommissionService cashCommissionService;

    public AdminCommissionDebtController(BidRepository bidRepo,
                                          AnnouncementRepository announcementRepo,
                                          CashCommissionService cashCommissionService) {
        this.bidRepo = bidRepo;
        this.announcementRepo = announcementRepo;
        this.cashCommissionService = cashCommissionService;
    }

    @GetMapping("/admin/commission-debts")
    public ResponseEntity<List<CommissionDebtResponse>> listCommissionDebts() {
        List<BidEntity> failedBids = bidRepo.findByCommissionStatus(CommissionStatus.FAILED);

        List<CommissionDebtResponse> result = failedBids.stream()
                .sorted(Comparator.comparing(BidEntity::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toDebtResponse)
                .toList();

        return ResponseEntity.ok(result);
    }

    private CommissionDebtResponse toDebtResponse(BidEntity bid) {
        AnnouncementEntity announcement = bid.getAnnouncementId() != null
                ? announcementRepo.findById(bid.getAnnouncementId()).orElse(null)
                : null;

        UUID travelerId = announcement != null ? announcement.getTravelerId() : null;

        // Montant dû recalculé au taux figé sur le bid (fixé lors de la tentative de
        // prélèvement) — computeBidCommission(bid, announcement) re-fige/relit ce taux
        // sans effet de bord persisté ici (pas de bidRepo.save). Si l'annonce est
        // introuvable (supprimée), on ne peut pas recalculer la base nette → montant null.
        BigDecimal amountOwedEur = announcement != null
                ? cashCommissionService.computeBidCommission(bid, announcement)
                : null;

        return new CommissionDebtResponse(
                bid.getId(),
                travelerId,
                amountOwedEur,
                bid.getCommissionRetryCount(),
                bid.getUpdatedAt());
    }
}
