package com.dony.api.triptemplate;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "trip_templates")
@Where(clause = "deleted_at IS NULL")
public class TripTemplateEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "label", nullable = false, length = 60)
    private String label;

    @Column(name = "emoji", length = 8)
    private String emoji;

    @Column(name = "departure_city", nullable = false, length = 100)
    private String departureCity;

    @Column(name = "departure_lat")
    private Double departureLat;

    @Column(name = "departure_lng")
    private Double departureLng;

    @Column(name = "arrival_city", nullable = false, length = 100)
    private String arrivalCity;

    @Column(name = "arrival_lat")
    private Double arrivalLat;

    @Column(name = "arrival_lng")
    private Double arrivalLng;

    @Column(name = "transport_mode", nullable = false, length = 20)
    private String transportMode = "PLANE";

    @Column(name = "capacity_unit", nullable = false, length = 20)
    private String capacityUnit = "SUITCASE_23KG";

    @Column(name = "available_kg", nullable = false)
    private Integer availableKg = 23;

    @Column(name = "price_per_kg", nullable = false)
    private Double pricePerKg;

    @Column(name = "accepted_categories", columnDefinition = "TEXT")
    private String acceptedCategories;

    @Column(name = "cash_accepted", nullable = false)
    private boolean cashAccepted = false;

    @Column(name = "arrival_time")
    private LocalTime arrivalTime;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }
    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }
    public Double getDepartureLat() { return departureLat; }
    public void setDepartureLat(Double departureLat) { this.departureLat = departureLat; }
    public Double getDepartureLng() { return departureLng; }
    public void setDepartureLng(Double departureLng) { this.departureLng = departureLng; }
    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String arrivalCity) { this.arrivalCity = arrivalCity; }
    public Double getArrivalLat() { return arrivalLat; }
    public void setArrivalLat(Double arrivalLat) { this.arrivalLat = arrivalLat; }
    public Double getArrivalLng() { return arrivalLng; }
    public void setArrivalLng(Double arrivalLng) { this.arrivalLng = arrivalLng; }
    public String getTransportMode() { return transportMode; }
    public void setTransportMode(String transportMode) { this.transportMode = transportMode; }
    public String getCapacityUnit() { return capacityUnit; }
    public void setCapacityUnit(String capacityUnit) { this.capacityUnit = capacityUnit; }
    public Integer getAvailableKg() { return availableKg; }
    public void setAvailableKg(Integer availableKg) { this.availableKg = availableKg; }
    public Double getPricePerKg() { return pricePerKg; }
    public void setPricePerKg(Double pricePerKg) { this.pricePerKg = pricePerKg; }
    public String getAcceptedCategories() { return acceptedCategories; }
    public void setAcceptedCategories(String acceptedCategories) { this.acceptedCategories = acceptedCategories; }
    public boolean isCashAccepted() { return cashAccepted; }
    public void setCashAccepted(boolean cashAccepted) { this.cashAccepted = cashAccepted; }
    public LocalTime getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(LocalTime arrivalTime) { this.arrivalTime = arrivalTime; }
}
