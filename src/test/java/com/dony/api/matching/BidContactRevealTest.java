package com.dony.api.matching;

import com.dony.api.auth.FirebaseContactService;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.CancellationRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le numéro ne voyage plus dans les réponses de colis : celles-ci ne portent qu'un
 * booléen « joignable », et le numéro s'obtient par un appel dédié, au moment où
 * l'utilisateur veut téléphoner.
 *
 * <p>Ces tests verrouillent les deux moitiés du contrat : aucune lecture Firebase au
 * rendu d'une liste, et une révélation soumise à ownership + statut + journalisation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BidService — le numéro n'est révélé qu'à la demande")
class BidContactRevealTest {

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
    private UserEntity traveler;
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        sender = user("uid-sender");
        traveler = user("uid-traveler");
        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(traveler.getId());
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");
        announcement.setAvailableKg(new BigDecimal("10"));
        announcement.setTotalKg(new BigDecimal("10"));
    }

    private UserEntity user(String uid) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirebaseUid(uid);
        return u;
    }

    private BidEntity bid(BidStatus status) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setSenderId(sender.getId());
        b.setAnnouncementId(announcement.getId());
        b.setStatus(status);
        b.setWeightKg(new BigDecimal("2"));
        return b;
    }

    // ── Les listes ne lisent plus aucun numéro ───────────────────────────────

    @Test
    @DisplayName("lister ses colis livrables ne déclenche aucun appel Firebase")
    void getMyBids_neverCallsFirebase() {
        BidEntity accepted = bid(BidStatus.ACCEPTED);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findBySenderId(sender.getId())).thenReturn(List.of(accepted));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));

        var result = bidService.getMyBids("uid-sender");

        assertThat(result).hasSize(1);
        // Même sur un colis accepté : le numéro n'est pas lu, seul le booléen est posé.
        verifyNoInteractions(firebaseContact);
        assertThat(result.get(0).senderPhoneAvailable()).isTrue();
        assertThat(result.get(0).travelerPhoneAvailable()).isTrue();
    }

    @Test
    @DisplayName("avant acceptation, les deux parties sont marquées non joignables")
    void toResponse_beforeAcceptance_marksBothUnavailable() {
        BidEntity pending = bid(BidStatus.PENDING);
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(bidRepository.findBySenderId(sender.getId())).thenReturn(List.of(pending));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));

        var result = bidService.getMyBids("uid-sender").get(0);

        assertThat(result.senderPhoneAvailable()).isFalse();
        assertThat(result.travelerPhoneAvailable()).isFalse();
        verifyNoInteractions(firebaseContact);
    }

    // ── La révélation à la demande ───────────────────────────────────────────

    @Test
    @DisplayName("l'expéditeur obtient le numéro du voyageur, et l'accès est journalisé")
    void getCounterpartyPhone_senderGetsTravelerNumber() {
        BidEntity accepted = bid(BidStatus.ACCEPTED);
        when(bidRepository.findById(accepted.getId())).thenReturn(Optional.of(accepted));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(firebaseContact.getContact("uid-traveler"))
                .thenReturn(new FirebaseContactService.Contact("+221701234567", null));

        var result = bidService.getCounterpartyPhone(accepted.getId(), "uid-sender");

        assertThat(result.phoneNumber()).isEqualTo("+221701234567");
        verify(auditService).log(eq("BID"), eq(accepted.getId()), eq("CONTACT_PHONE_REVEALED"),
                eq(sender.getId()), any());
    }

    @Test
    @DisplayName("le voyageur obtient le numéro de l'expéditeur")
    void getCounterpartyPhone_travelerGetsSenderNumber() {
        BidEntity accepted = bid(BidStatus.HANDED_OVER);
        when(bidRepository.findById(accepted.getId())).thenReturn(Optional.of(accepted));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));
        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));
        when(firebaseContact.getContact("uid-sender"))
                .thenReturn(new FirebaseContactService.Contact("+33612345678", null));

        var result = bidService.getCounterpartyPhone(accepted.getId(), "uid-traveler");

        assertThat(result.phoneNumber()).isEqualTo("+33612345678");
    }

    @Test
    @DisplayName("avant acceptation → 403, et Firebase n'est pas interrogé")
    void getCounterpartyPhone_beforeAcceptance_forbidden() {
        BidEntity pending = bid(BidStatus.PENDING);
        when(bidRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));

        assertThatThrownBy(() -> bidService.getCounterpartyPhone(pending.getId(), "uid-sender"))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> {
                    assertThat(((DonyBusinessException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("phone-not-revealable");
                });
        verifyNoInteractions(firebaseContact);
    }

    @Test
    @DisplayName("un tiers au colis → 403, et rien n'est journalisé")
    void getCounterpartyPhone_thirdParty_forbidden() {
        BidEntity accepted = bid(BidStatus.ACCEPTED);
        UserEntity intruder = user("uid-intruder");
        when(bidRepository.findById(accepted.getId())).thenReturn(Optional.of(accepted));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid("uid-intruder")).thenReturn(Optional.of(intruder));

        assertThatThrownBy(() -> bidService.getCounterpartyPhone(accepted.getId(), "uid-intruder"))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("forbidden"));
        verifyNoInteractions(firebaseContact, auditService);
    }

    @Test
    @DisplayName("la révélation tourne en transaction d'écriture (elle journalise)")
    void getCounterpartyPhone_isNotReadOnlyTransaction() throws Exception {
        // Régression : la méthode était @Transactional(readOnly = true) alors qu'elle
        // insère dans audit_log. PostgreSQL refuse alors l'INSERT (« cannot execute
        // INSERT in a read-only transaction ») et l'appel échoue en 500, après avoir
        // pourtant lu le numéro.
        //
        // Aucun test unitaire ne peut attraper ça — AuditService y est un mock, il ne
        // touche aucune transaction — et H2 (profil test) n'applique pas readOnly
        // comme PostgreSQL. D'où cette vérification directe de l'annotation.
        var method = BidService.class.getMethod("getCounterpartyPhone", UUID.class, String.class);
        var tx = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(tx).as("getCounterpartyPhone doit être transactionnelle").isNotNull();
        assertThat(tx.readOnly())
                .as("readOnly interdit : la méthode écrit une entrée audit_log")
                .isFalse();
    }

    @Test
    @DisplayName("compte sans numéro côté Firebase → réponse à null, pas d'erreur")
    void getCounterpartyPhone_noNumberOnAccount_returnsNull() {
        BidEntity accepted = bid(BidStatus.COMPLETED);
        when(bidRepository.findById(accepted.getId())).thenReturn(Optional.of(accepted));
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));
        when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        when(userRepository.findById(traveler.getId())).thenReturn(Optional.of(traveler));
        when(firebaseContact.getContact("uid-traveler")).thenReturn(FirebaseContactService.Contact.EMPTY);

        assertThat(bidService.getCounterpartyPhone(accepted.getId(), "uid-sender").phoneNumber()).isNull();
    }
}
