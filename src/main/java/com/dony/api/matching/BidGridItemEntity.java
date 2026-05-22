package com.dony.api.matching;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "bid_grid_items")
public class BidGridItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "announcement_grid_item_id", nullable = false)
    private UUID announcementGridItemId;

    @Column(name = "label_snapshot", nullable = false, length = 100)
    private String labelSnapshot;

    @Column(name = "unit_price_net_snapshot", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPriceNetSnapshot;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(ZoneOffset.UTC); }

    public UUID getId() { return id; }
    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public UUID getAnnouncementGridItemId() { return announcementGridItemId; }
    public void setAnnouncementGridItemId(UUID announcementGridItemId) { this.announcementGridItemId = announcementGridItemId; }
    public String getLabelSnapshot() { return labelSnapshot; }
    public void setLabelSnapshot(String labelSnapshot) { this.labelSnapshot = labelSnapshot; }
    public BigDecimal getUnitPriceNetSnapshot() { return unitPriceNetSnapshot; }
    public void setUnitPriceNetSnapshot(BigDecimal unitPriceNetSnapshot) { this.unitPriceNetSnapshot = unitPriceNetSnapshot; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
