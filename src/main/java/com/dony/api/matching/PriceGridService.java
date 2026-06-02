package com.dony.api.matching;

import com.dony.api.common.AuditService;
import com.dony.api.config.DonyConfigProperties;
import com.dony.api.matching.dto.AnnouncementPriceGridItemResponse;
import com.dony.api.matching.dto.PriceGridItemRequest;
import com.dony.api.matching.dto.PriceGridItemResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PriceGridService {

    private static final int MAX_GRID_ITEMS = 20;

    private final PriceGridItemRepository gridRepo;
    private final AnnouncementPriceGridItemRepository annGridRepo;
    private final AuditService auditService;
    private final DonyConfigProperties config;

    public PriceGridService(PriceGridItemRepository gridRepo,
                            AnnouncementPriceGridItemRepository annGridRepo,
                            AuditService auditService,
                            DonyConfigProperties config) {
        this.gridRepo = gridRepo;
        this.annGridRepo = annGridRepo;
        this.auditService = auditService;
        this.config = config;
    }

    public List<PriceGridItemResponse> getItems(UUID travelerId) {
        return gridRepo.findByTravelerIdOrderByPositionAsc(travelerId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public PriceGridItemResponse addItem(UUID travelerId, PriceGridItemRequest req, UUID actorId) {
        long currentCount = gridRepo.countByTravelerId(travelerId);
        if (currentCount >= MAX_GRID_ITEMS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "price-grid-limit: maximum 20 articles autorisés");
        }
        int position = (int) currentCount;
        PriceGridItemEntity entity = new PriceGridItemEntity();
        entity.setTravelerId(travelerId);
        entity.setLabel(req.label());
        entity.setUnitPriceNet(req.unitPriceNet());
        entity.setPosition(position);
        PriceGridItemEntity saved = gridRepo.save(entity);
        auditService.log(
            "PRICE_GRID_ITEM",
            saved.getId(),
            "PRICE_GRID_ITEM_CREATED",
            actorId,
            java.util.Map.<String, Object>of("label", req.label(), "unitPriceNet", req.unitPriceNet().toString())
        );
        return toResponse(saved);
    }

    @Transactional
    public PriceGridItemResponse updateItem(UUID travelerId, UUID itemId,
                                            PriceGridItemRequest req, UUID actorId) {
        PriceGridItemEntity entity = gridRepo.findById(itemId)
                .filter(e -> e.getTravelerId().equals(travelerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "price-grid-item-not-found"));
        entity.setLabel(req.label());
        entity.setUnitPriceNet(req.unitPriceNet());
        PriceGridItemEntity saved = gridRepo.save(entity);
        auditService.log(
            "PRICE_GRID_ITEM",
            saved.getId(),
            "PRICE_GRID_ITEM_UPDATED",
            actorId,
            java.util.Map.<String, Object>of("label", req.label(), "unitPriceNet", req.unitPriceNet().toString())
        );
        return toResponse(saved);
    }

    @Transactional
    public void deleteItem(UUID travelerId, UUID itemId, UUID actorId) {
        PriceGridItemEntity entity = gridRepo.findById(itemId)
                .filter(e -> e.getTravelerId().equals(travelerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "price-grid-item-not-found"));
        entity.softDelete();
        gridRepo.save(entity);
        auditService.log(
            "PRICE_GRID_ITEM",
            itemId,
            "PRICE_GRID_ITEM_DELETED",
            actorId,
            java.util.Map.<String, Object>of("label", entity.getLabel())
        );
    }

    @Transactional
    public void snapshotToAnnouncement(UUID travelerId, UUID announcementId) {
        List<PriceGridItemEntity> items = gridRepo.findByTravelerIdOrderByPositionAsc(travelerId);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "price-grid-empty: au moins 1 article requis pour le mode MIXED");
        }
        List<AnnouncementPriceGridItemEntity> snapshots = items.stream().map(item -> {
            AnnouncementPriceGridItemEntity snap = new AnnouncementPriceGridItemEntity();
            snap.setAnnouncementId(announcementId);
            snap.setLabel(item.getLabel());
            snap.setUnitPriceNet(item.getUnitPriceNet());
            snap.setPosition(item.getPosition());
            return snap;
        }).toList();
        annGridRepo.deleteByAnnouncementId(announcementId);
        annGridRepo.saveAll(snapshots);
        auditService.log(
            "ANNOUNCEMENT",
            announcementId,
            "ANNOUNCEMENT_PRICE_GRID_SNAPSHOTTED",
            travelerId,
            java.util.Map.<String, Object>of("itemCount", String.valueOf(items.size()))
        );
    }

    @Transactional
    public List<PriceGridItemResponse> reorder(UUID travelerId, List<UUID> orderedIds) {
        List<PriceGridItemEntity> items = gridRepo.findByTravelerIdOrderByPositionAsc(travelerId);
        for (int i = 0; i < orderedIds.size(); i++) {
            final int pos = i;
            UUID id = orderedIds.get(i);
            items.stream().filter(e -> e.getId().equals(id)).findFirst()
                 .ifPresent(e -> e.setPosition(pos));
        }
        return gridRepo.saveAll(items).stream().map(this::toResponse).toList();
    }

    public List<AnnouncementPriceGridItemResponse> getAnnouncementGridItems(UUID announcementId) {
        return annGridRepo.findByAnnouncementIdOrderByPositionAsc(announcementId)
                .stream().map(e -> new AnnouncementPriceGridItemResponse(
                        e.getId(), e.getLabel(), e.getUnitPriceNet(), displayPrice(e.getUnitPriceNet())
                )).toList();
    }

    private PriceGridItemResponse toResponse(PriceGridItemEntity e) {
        return new PriceGridItemResponse(e.getId(), e.getLabel(), e.getUnitPriceNet(),
                displayPrice(e.getUnitPriceNet()), e.getPosition());
    }

    /**
     * Prix « affiché expéditeur » = net × (1 + commission Dony). Le taux provient de la
     * config {@code dony.commission.rate} — SOURCE UNIQUE du pourcentage de commission,
     * ajustable sans toucher au code (même valeur que le montant facturé par PaymentService).
     */
    BigDecimal displayPrice(BigDecimal netPrice) {
        BigDecimal multiplier = BigDecimal.ONE.add(config.commission().rate());
        return netPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }
}
