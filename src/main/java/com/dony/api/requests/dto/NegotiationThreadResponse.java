package com.dony.api.requests.dto;

import com.dony.api.requests.entity.NegotiationThreadStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record NegotiationThreadResponse(
    UUID id, UUID packageRequestId, UUID travelerId,
    UUID travelerAnnouncementId, LocalDate travelerTravelDate, BigDecimal travelerAvailableKg,
    NegotiationThreadStatus status, BigDecimal currentPriceEur, int roundsCount,
    LocalDateTime lastActivityAt, LocalDateTime createdAt,
    List<NegotiationMessageResponse> messages,
    String paymentIntentClientSecret,
    // Profil voyageur embarqué
    String travelerName, BigDecimal travelerRating, Integer travelerTripsCount, String travelerPhotoUrl,
    // Infos demande embarquées
    String departureCity, String arrivalCity, BigDecimal weightKg,
    // Profil expéditeur embarqué (affiché côté voyageur)
    String senderName,
    // Champs calculés selon le callerId — source de vérité unique pour les clients
    boolean isMyTurn,
    boolean canAccept,
    boolean canCounter,
    int roundsRemaining,
    // Détails du trajet lié (null si aucun trajet lié)
    LinkedTripSummary linkedTrip
) {}
