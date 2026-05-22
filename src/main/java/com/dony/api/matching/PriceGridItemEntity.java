package com.dony.api.matching;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "traveler_price_grid_items")
@org.hibernate.annotations.Where(clause = "deleted_at IS NULL")
public class PriceGridItemEntity extends BaseEntity {

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "unit_price_net", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPriceNet;

    @Column(name = "position", nullable = false)
    private int position;

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public BigDecimal getUnitPriceNet() { return unitPriceNet; }
    public void setUnitPriceNet(BigDecimal unitPriceNet) { this.unitPriceNet = unitPriceNet; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}
