package com.dony.api.matching;

import com.dony.api.common.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "announcements")
@Where(clause = "deleted_at IS NULL")
public class AnnouncementEntity extends BaseEntity {

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "departure_city", nullable = false, length = 100)
    private String departureCity;

    @Column(name = "arrival_city", nullable = false, length = 100)
    private String arrivalCity;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(name = "departure_time")
    private LocalTime departureTime;

    @Column(name = "arrival_time")
    private LocalTime arrivalTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", nullable = false, length = 20)
    private TransportMode transportMode;

    @Column(name = "pickup_address_label", nullable = false, length = 500)
    private String pickupAddressLabel;

    @Column(name = "pickup_lat", nullable = false, precision = 9, scale = 6)
    private java.math.BigDecimal pickupLat;

    @Column(name = "pickup_lng", nullable = false, precision = 9, scale = 6)
    private java.math.BigDecimal pickupLng;

    @Column(name = "delivery_address_label", nullable = false, length = 500)
    private String deliveryAddressLabel;

    @Column(name = "delivery_lat", nullable = false, precision = 9, scale = 6)
    private java.math.BigDecimal deliveryLat;

    @Column(name = "delivery_lng", nullable = false, precision = 9, scale = 6)
    private java.math.BigDecimal deliveryLng;

    @Column(name = "available_kg", nullable = false, precision = 5, scale = 2)
    private BigDecimal availableKg;

    @Column(name = "price_per_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerKg;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AnnouncementStatus status = AnnouncementStatus.ACTIVE;

    @Column(name = "description", length = 500)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "announcement_accepted_types",
            joinColumns = @JoinColumn(name = "announcement_id")
    )
    @Column(name = "content_type", length = 100)
    private List<String> acceptedContentTypes = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "announcement_refused_types",
            joinColumns = @JoinColumn(name = "announcement_id")
    )
    @Column(name = "content_type", length = 100)
    private List<String> refusedTypes = new ArrayList<>();

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }

    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String arrivalCity) { this.arrivalCity = arrivalCity; }

    public LocalDate getDepartureDate() { return departureDate; }
    public void setDepartureDate(LocalDate departureDate) { this.departureDate = departureDate; }

    public LocalTime getDepartureTime() { return departureTime; }
    public void setDepartureTime(LocalTime departureTime) { this.departureTime = departureTime; }

    public LocalTime getArrivalTime() { return arrivalTime; }
    public void setArrivalTime(LocalTime arrivalTime) { this.arrivalTime = arrivalTime; }

    public TransportMode getTransportMode() { return transportMode; }
    public void setTransportMode(TransportMode transportMode) { this.transportMode = transportMode; }

    public String getPickupAddressLabel() { return pickupAddressLabel; }
    public void setPickupAddressLabel(String v) { this.pickupAddressLabel = v; }
    public java.math.BigDecimal getPickupLat() { return pickupLat; }
    public void setPickupLat(java.math.BigDecimal v) { this.pickupLat = v; }
    public java.math.BigDecimal getPickupLng() { return pickupLng; }
    public void setPickupLng(java.math.BigDecimal v) { this.pickupLng = v; }
    public String getDeliveryAddressLabel() { return deliveryAddressLabel; }
    public void setDeliveryAddressLabel(String v) { this.deliveryAddressLabel = v; }
    public java.math.BigDecimal getDeliveryLat() { return deliveryLat; }
    public void setDeliveryLat(java.math.BigDecimal v) { this.deliveryLat = v; }
    public java.math.BigDecimal getDeliveryLng() { return deliveryLng; }
    public void setDeliveryLng(java.math.BigDecimal v) { this.deliveryLng = v; }

    public BigDecimal getAvailableKg() { return availableKg; }
    public void setAvailableKg(BigDecimal availableKg) { this.availableKg = availableKg; }

    public BigDecimal getPricePerKg() { return pricePerKg; }
    public void setPricePerKg(BigDecimal pricePerKg) { this.pricePerKg = pricePerKg; }

    public AnnouncementStatus getStatus() { return status; }
    public void setStatus(AnnouncementStatus status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getAcceptedContentTypes() { return acceptedContentTypes; }
    public void setAcceptedContentTypes(List<String> acceptedContentTypes) { this.acceptedContentTypes = acceptedContentTypes; }

    public List<String> getRefusedTypes() { return refusedTypes; }
    public void setRefusedTypes(List<String> refusedTypes) { this.refusedTypes = refusedTypes; }
}
