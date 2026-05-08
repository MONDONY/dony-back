package com.dony.api.city;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "corridors")
public class CorridorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "departure_city", nullable = false, length = 200)
    private String departureCity;

    @Column(name = "departure_country", nullable = false, length = 100)
    private String departureCountry;

    @Column(name = "arrival_city", nullable = false, length = 200)
    private String arrivalCity;

    @Column(name = "arrival_country", nullable = false, length = 100)
    private String arrivalCountry;

    @Column(name = "usage_count", nullable = false)
    private Integer usageCount = 1;

    @Column(name = "last_used_at", nullable = false)
    private OffsetDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        lastUsedAt = createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getDepartureCity() { return departureCity; }
    public void setDepartureCity(String departureCity) { this.departureCity = departureCity; }
    public String getDepartureCountry() { return departureCountry; }
    public void setDepartureCountry(String departureCountry) { this.departureCountry = departureCountry; }
    public String getArrivalCity() { return arrivalCity; }
    public void setArrivalCity(String arrivalCity) { this.arrivalCity = arrivalCity; }
    public String getArrivalCountry() { return arrivalCountry; }
    public void setArrivalCountry(String arrivalCountry) { this.arrivalCountry = arrivalCountry; }
    public Integer getUsageCount() { return usageCount; }
    public void setUsageCount(Integer usageCount) { this.usageCount = usageCount; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
