package com.dony.api.alerts;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "corridor_alerts")
@Where(clause = "deleted_at IS NULL")
public class CorridorAlertEntity extends BaseEntity {

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "departure_city", nullable = false, length = 100)
    private String departureCity;

    @Column(name = "departure_country_code", length = 2)
    private String departureCountryCode;

    @Column(name = "arrival_city", nullable = false, length = 100)
    private String arrivalCity;

    @Column(name = "arrival_country_code", length = 2)
    private String arrivalCountryCode;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Column(name = "min_weight_kg", precision = 5, scale = 2)
    private BigDecimal minWeightKg;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_notified_at")
    private LocalDateTime lastNotifiedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "corridor_alert_content_categories",
            joinColumns = @JoinColumn(name = "alert_id")
    )
    @Column(name = "content_category", length = 100)
    private List<String> contentCategories = new ArrayList<>();

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }

    public String getDepartureCountryCode() { return departureCountryCode; }
    public void setDepartureCountryCode(String departureCountryCode) { this.departureCountryCode = departureCountryCode; }

    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String arrivalCity) { this.arrivalCity = arrivalCity; }

    public String getArrivalCountryCode() { return arrivalCountryCode; }
    public void setArrivalCountryCode(String arrivalCountryCode) { this.arrivalCountryCode = arrivalCountryCode; }

    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }

    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }

    public BigDecimal getMinWeightKg() { return minWeightKg; }
    public void setMinWeightKg(BigDecimal minWeightKg) { this.minWeightKg = minWeightKg; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getLastNotifiedAt() { return lastNotifiedAt; }
    public void setLastNotifiedAt(LocalDateTime lastNotifiedAt) { this.lastNotifiedAt = lastNotifiedAt; }

    public List<String> getContentCategories() { return contentCategories; }
    public void setContentCategories(List<String> contentCategories) {
        this.contentCategories = contentCategories != null ? new ArrayList<>(contentCategories) : new ArrayList<>();
    }
}
