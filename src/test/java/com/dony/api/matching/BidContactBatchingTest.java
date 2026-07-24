package com.dony.api.matching;

import com.dony.api.auth.FirebaseContactService;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.CancellationRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.StorageService;
import com.dony.api.ratings.RatingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le téléphone vit dans Firebase, plus en base : un endpoint de liste doit résoudre
 * les coordonnées de toutes ses contreparties en UN aller-retour, pas un par ligne.
 * Ces tests verrouillent ce contrat — sans eux, le N+1 revient sans bruit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BidService — coordonnées résolues en lot sur les listes")
class BidContactBatchingTest {

    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private RatingRepository ratingRepository;
    @Mock private CancellationRepository cancellationRepository;
    @Mock private BidGridItemRepository bidGridItemRepository;
    @Mock private AnnouncementPriceGridItemRepository annGridItemRepository;
    @Mock private StorageService storageService;
    @Mock private BidPhotoService bidPhotoService;
    @Mock private com.dony.api.common.CommissionRateResolver commissionRateResolver;
    @Mock private FirebaseContactService firebaseContact;

    @InjectMocks private BidService bidService;

    private UserEntity sender;

    @BeforeEach
    void setUp() {
        sender = user("uid-sender");
    }

    private UserEntity user(String uid) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirebaseUid(uid);
        return u;
    }

    private AnnouncementEntity announcement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setTravelerId(travelerId);
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setAvailableKg(new BigDecimal("10"));
        a.setTotalKg(new BigDecimal("10"));
        return a;
    }

    private BidEntity bid(UUID announcementId, BidStatus status) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setSenderId(sender.getId());
        b.setAnnouncementId(announcementId);
        b.setStatus(status);
        b.setWeightKg(new BigDecimal("2"));
        return b;
    }

    @Test
    @DisplayName("plusieurs colis livrables, voyageurs distincts → un seul appel Firebase")
    void getMyBids_severalRevealingBids_hitsFirebaseOnce() {
        List<UserEntity> travelers = new ArrayList<>();
        List<AnnouncementEntity> announcements = new ArrayList<>();
        List<BidEntity> bids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UserEntity t = user("uid-traveler-" + i);
            AnnouncementEntity a = announcement(t.getId());
            travelers.add(t);
            announcements.add(a);
            bids.add(bid(a.getId(), BidStatus.ACCEPTED));
        }

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findBySenderId(sender.getId())).thenReturn(bids);
        when(announcementRepository.findAllById(any())).thenReturn(announcements);

        List<UserEntity> everyone = new ArrayList<>(travelers);
        everyone.add(sender);
        when(userRepository.findAllById(any())).thenReturn(everyone);

        Map<String, FirebaseContactService.Contact> batch = new HashMap<>();
        batch.put("uid-sender", new FirebaseContactService.Contact("+33600000000", null));
        for (int i = 0; i < 3; i++) {
            batch.put("uid-traveler-" + i,
                    new FirebaseContactService.Contact("+2217000000" + i, null));
        }
        when(firebaseContact.getContacts(any())).thenReturn(batch);

        for (int i = 0; i < 3; i++) {
            when(announcementRepository.findById(announcements.get(i).getId()))
                    .thenReturn(Optional.of(announcements.get(i)));
            when(userRepository.findById(travelers.get(i).getId()))
                    .thenReturn(Optional.of(travelers.get(i)));
        }

        List<com.dony.api.matching.dto.BidResponse> result = bidService.getMyBids("uid-sender");

        assertThat(result).hasSize(3);
        // Le cœur du contrat : un lot, zéro appel unitaire, quel que soit le nombre de lignes.
        verify(firebaseContact, times(1)).getContacts(any());
        verify(firebaseContact, never()).getContact(anyString());
    }

    @Test
    @DisplayName("le numéro du lot arrive bien dans la réponse")
    void getMyBids_phoneFromBatchReachesResponse() {
        UserEntity traveler = user("uid-traveler");
        AnnouncementEntity ann = announcement(traveler.getId());
        BidEntity accepted = bid(ann.getId(), BidStatus.ACCEPTED);

        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findBySenderId(sender.getId())).thenReturn(List.of(accepted));
        when(announcementRepository.findAllById(any())).thenReturn(List.of(ann));
        when(userRepository.findAllById(any())).thenReturn(List.of(sender, traveler));
        when(firebaseContact.getContacts(any())).thenReturn(Map.of(
                "uid-sender", new FirebaseContactService.Contact("+33600000000", null),
                "uid-traveler", new FirebaseContactService.Contact("+221701234567", null)));
        when(announcementRepository.findById(ann.getId())).thenReturn(Optional.of(ann));
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));

        var response = bidService.getMyBids("uid-sender").get(0);

        assertThat(response.senderPhone()).isEqualTo("+33600000000");
        assertThat(response.travelerPhone()).isEqualTo("+221701234567");
    }

    @Test
    @DisplayName("aucun colis au statut révélant → aucun appel Firebase ni requête de pré-chargement")
    void getMyBids_noRevealingBid_touchesNothing() {
        AnnouncementEntity ann = announcement(UUID.randomUUID());
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findBySenderId(sender.getId()))
                .thenReturn(List.of(bid(ann.getId(), BidStatus.PENDING),
                        bid(ann.getId(), BidStatus.AWAITING_PAYMENT)));

        List<com.dony.api.matching.dto.BidResponse> result = bidService.getMyBids("uid-sender");

        assertThat(result).hasSize(2);
        // Le cas courant ne doit rien coûter : ni appel réseau, ni les 2 requêtes SQL
        // de pré-chargement.
        verifyNoInteractions(firebaseContact);
        verify(announcementRepository, never()).findAllById(any());
        verify(userRepository, never()).findAllById(any());
    }
}
