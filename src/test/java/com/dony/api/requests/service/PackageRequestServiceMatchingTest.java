package com.dony.api.requests.service;

import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.StorageService;
import com.dony.api.config.DonyConfigProperties;
import com.dony.api.favorites.FavoriteRepository;
import com.dony.api.favorites.FavoriteTargetType;
import com.dony.api.matching.MatchingService;
import com.dony.api.matching.TransportMode;
import com.dony.api.payments.cash.PaymentMethod;
import com.dony.api.requests.RequestsConfig;
import com.dony.api.requests.dto.PackageRequestSearchResponse;
import com.dony.api.requests.entity.PackageRequestEntity;
import com.dony.api.requests.entity.PackageRequestStatus;
import com.dony.api.requests.entity.ParcelSize;
import com.dony.api.requests.repository.NegotiationThreadRepository;
import com.dony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ces tests portent sur le tri, la pagination et la propagation du score de
 * {@link PackageRequestService#searchMatchingMyTrips}. Ils reprennent le
 * harnais de mocks de {@link PackageRequestServiceTest}, plus le mock de
 * {@link MatchingService} qu'introduit cette méthode.
 */
@ExtendWith(MockitoExtension.class)
class PackageRequestServiceMatchingTest {

    @Mock private PackageRequestRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditService auditService;
    @Mock private RequestsConfig config;
    @Mock private NegotiationThreadRepository threadRepository;
    @Mock private com.dony.api.city.CityRepository cityRepository;
    @Mock private com.dony.api.payments.cash.CommissionProperties commissionProperties;
    @Mock private StorageService storageService;
    @Mock private PackageRequestPhotoService photoService;
    @Mock private FavoriteRepository favoriteRepository;
    @Mock private MatchingService matchingService;

    /** Real record (not mocked) — threshold-days=3 mirrors application-test.yml (dony.urgency.threshold-days). */
    private final DonyConfigProperties donyConfig =
            new DonyConfigProperties(null, null, new DonyConfigProperties.Urgency(3));
    private PackageRequestService service;

    private UserEntity sender;
    private final UUID SENDER_ID = UUID.randomUUID();
    private final UUID CALLER_ID = UUID.randomUUID();

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PackageRequestEntity buildEntity(UUID id) {
        PackageRequestEntity entity = new PackageRequestEntity();
        setId(entity, id);
        entity.setSenderId(SENDER_ID);
        entity.setDepartureCity("Paris");
        entity.setArrivalCity("Dakar");
        entity.setDesiredDate(LocalDate.now().plusDays(7));
        entity.setDateToleranceDays((short) 2);
        entity.setWeightKg(new BigDecimal("5"));
        entity.setParcelSize(ParcelSize.SMALL);
        entity.setTransportMode(TransportMode.PLANE);
        entity.setContentCategory("vetements");
        entity.setNegotiable(true);
        entity.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        entity.setStatus(PackageRequestStatus.OPEN);
        return entity;
    }

    @BeforeEach
    void setup() {
        sender = new UserEntity();
        setId(sender, SENDER_ID);
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(photoService.activePhotosBatch(any())).thenReturn(Map.of());
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of(sender));
        lenient().when(cityRepository.findByNamesIgnoreCaseBatch(any())).thenReturn(Map.of());
        lenient().when(favoriteRepository.findTargetIds(any(), any())).thenReturn(List.of());

        PackageRequestSearchMapper realMapper = new PackageRequestSearchMapper(
                userRepository, cityRepository, storageService, photoService, donyConfig);
        service = new PackageRequestService(
                repository, userRepository, eventPublisher, auditService, config,
                threadRepository, cityRepository, commissionProperties,
                storageService, photoService, favoriteRepository, realMapper, matchingService);
    }

    @Test
    void searchMatchingMyTrips_trieParScoreDecroissant() {
        // Arrange : 3 demandes, scores 40 / 94 / 70, renvoyées par le repo dans le désordre.
        UUID req1 = UUID.randomUUID(); // score 40
        UUID req2 = UUID.randomUUID(); // score 94
        UUID req3 = UUID.randomUUID(); // score 70

        PackageRequestEntity e1 = buildEntity(req1);
        PackageRequestEntity e2 = buildEntity(req2);
        PackageRequestEntity e3 = buildEntity(req3);

        // Le mock de matchingService.findBestMatchByRequestId retourne la map ordonnée
        // par score décroissant (contrat de la Task 1).
        Map<UUID, MatchingService.MatchInfo> matches = new LinkedHashMap<>();
        matches.put(req2, new MatchingService.MatchInfo(req2, UUID.randomUUID(), LocalDate.now().plusDays(3), 94));
        matches.put(req3, new MatchingService.MatchInfo(req3, UUID.randomUUID(), LocalDate.now().plusDays(5), 70));
        matches.put(req1, new MatchingService.MatchInfo(req1, UUID.randomUUID(), LocalDate.now().plusDays(1), 40));
        when(matchingService.findBestMatchByRequestId(CALLER_ID)).thenReturn(matches);

        // Le mock de repository.findAll(spec) retourne les 3 entités, dans le désordre.
        when(repository.findAll(any(Specification.class))).thenReturn(List.of(e1, e2, e3));

        // Act
        Page<PackageRequestSearchResponse> page = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, 20), CALLER_ID);

        // Assert : les ids sortent dans l'ordre 94, 70, 40, et chaque réponse porte son score.
        assertThat(page.getContent()).extracting(PackageRequestSearchResponse::id)
                .containsExactly(req2, req3, req1);
        assertThat(page.getContent().get(0).matchScore()).isEqualTo(94);
        assertThat(page.getContent().get(1).matchScore()).isEqualTo(70);
        assertThat(page.getContent().get(2).matchScore()).isEqualTo(40);
    }

    @Test
    void searchMatchingMyTrips_pagineApresLeTri() {
        // Arrange : 3 demandes scorées 94 / 70 / 40, page size 2.
        UUID req94 = UUID.randomUUID();
        UUID req70 = UUID.randomUUID();
        UUID req40 = UUID.randomUUID();

        PackageRequestEntity e94 = buildEntity(req94);
        PackageRequestEntity e70 = buildEntity(req70);
        PackageRequestEntity e40 = buildEntity(req40);

        Map<UUID, MatchingService.MatchInfo> matches = new LinkedHashMap<>();
        matches.put(req94, new MatchingService.MatchInfo(req94, UUID.randomUUID(), LocalDate.now().plusDays(3), 94));
        matches.put(req70, new MatchingService.MatchInfo(req70, UUID.randomUUID(), LocalDate.now().plusDays(5), 70));
        matches.put(req40, new MatchingService.MatchInfo(req40, UUID.randomUUID(), LocalDate.now().plusDays(1), 40));
        when(matchingService.findBestMatchByRequestId(CALLER_ID)).thenReturn(matches);
        when(repository.findAll(any(Specification.class))).thenReturn(List.of(e94, e70, e40));

        // Act : page 0 → [94, 70] ; page 1 → [40].
        Page<PackageRequestSearchResponse> page0 = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, 2), CALLER_ID);
        Page<PackageRequestSearchResponse> page1 = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(1, 2), CALLER_ID);

        // Assert : totalElements == 3 sur les deux pages.
        assertThat(page0.getContent()).extracting(PackageRequestSearchResponse::matchScore)
                .containsExactly(94, 70);
        assertThat(page0.getTotalElements()).isEqualTo(3);

        assertThat(page1.getContent()).extracting(PackageRequestSearchResponse::matchScore)
                .containsExactly(40);
        assertThat(page1.getTotalElements()).isEqualTo(3);
    }

    @Test
    void searchMatchingMyTrips_aucunTrajetActif_retournePageVide() {
        // Arrange : findBestMatchByRequestId retourne une map vide.
        when(matchingService.findBestMatchByRequestId(CALLER_ID)).thenReturn(Map.of());

        // Act
        Page<PackageRequestSearchResponse> page = service.searchMatchingMyTrips(
                Specification.where(null), PageRequest.of(0, 20), CALLER_ID);

        // Assert : page vide, totalElements == 0, et repository.findAll jamais appelé
        //          (court-circuit avant toute requête SQL).
        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(0);
        verifyNoInteractions(repository);
    }
}
