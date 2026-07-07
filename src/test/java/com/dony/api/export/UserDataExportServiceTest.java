package com.dony.api.export;

import com.dony.api.addressbook.delivery.DeliveryAddressEntity;
import com.dony.api.addressbook.delivery.DeliveryAddressRepository;
import com.dony.api.addressbook.pickup.PickupAddressEntity;
import com.dony.api.addressbook.pickup.PickupAddressRepository;
import com.dony.api.addressbook.recipient.RecipientEntity;
import com.dony.api.addressbook.recipient.RecipientRepository;
import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.export.dto.UserDataExportDto;
import com.dony.api.favorites.FavoriteEntity;
import com.dony.api.favorites.FavoriteRepository;
import com.dony.api.favorites.FavoriteTargetType;
import com.dony.api.kyc.KycRepository;
import com.dony.api.kyc.KycVerificationEntity;
import com.dony.api.kyc.KycVerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserDataExportService")
class UserDataExportServiceTest {

    @Mock private RecipientRepository recipientRepository;
    @Mock private PickupAddressRepository pickupAddressRepository;
    @Mock private DeliveryAddressRepository deliveryAddressRepository;
    @Mock private FavoriteRepository favoriteRepository;
    @Mock private KycRepository kycRepository;

    private UserDataExportService service() {
        return new UserDataExportService(
                recipientRepository, pickupAddressRepository, deliveryAddressRepository,
                favoriteRepository, kycRepository);
    }

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

    private UserEntity makeUser(UUID id) {
        UserEntity u = new UserEntity();
        setId(u, id);
        u.setFirstName("Aïssatou");
        u.setLastName("Ba");
        u.setEmail("aissatou@example.com");
        u.setPhoneNumber("+221771234567");
        u.setCity("Dakar");
        u.setCountry("SN");
        u.setRoles(new HashSet<>(List.of(Role.SENDER)));
        return u;
    }

    @Test
    @DisplayName("agrège profil, KYC, destinataires, adresses et favoris de l'utilisateur")
    void aggregatesAllCategories() {
        UUID userId = UUID.randomUUID();
        UserEntity user = makeUser(userId);

        RecipientEntity recipient = new RecipientEntity();
        recipient.setUserId(userId);
        recipient.setFullName("Maman");
        recipient.setPhoneE164("+221771111111");
        recipient.setCity("Dakar");
        recipient.setCountry("SN");
        when(recipientRepository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(recipient));

        PickupAddressEntity pickup = new PickupAddressEntity();
        pickup.setUserId(userId);
        pickup.setLabel("Domicile");
        pickup.setStreet("1 rue de Paris");
        pickup.setCity("Paris");
        pickup.setCountry("FR");
        when(pickupAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId))
                .thenReturn(List.of(pickup));

        DeliveryAddressEntity delivery = new DeliveryAddressEntity();
        delivery.setUserId(userId);
        delivery.setLabel("Bureau");
        delivery.setStreet("2 avenue Cheikh Anta Diop");
        delivery.setCity("Dakar");
        delivery.setCountry("SN");
        when(deliveryAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId))
                .thenReturn(List.of(delivery));

        FavoriteEntity favorite = new FavoriteEntity(userId, FavoriteTargetType.TRIP, UUID.randomUUID());
        when(favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(favorite));

        KycVerificationEntity kyc = new KycVerificationEntity();
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kyc));

        UserDataExportDto export = service().export(user);

        assertThat(export.profile().id()).isEqualTo(userId);
        assertThat(export.profile().firstName()).isEqualTo("Aïssatou");
        assertThat(export.profile().roles()).containsExactly("SENDER");
        assertThat(export.kyc().status()).isEqualTo("VERIFIED");
        assertThat(export.recipients()).hasSize(1);
        assertThat(export.recipients().get(0).fullName()).isEqualTo("Maman");
        assertThat(export.pickupAddresses()).hasSize(1);
        assertThat(export.pickupAddresses().get(0).label()).isEqualTo("Domicile");
        assertThat(export.deliveryAddresses()).hasSize(1);
        assertThat(export.deliveryAddresses().get(0).label()).isEqualTo("Bureau");
        assertThat(export.favorites()).hasSize(1);
        assertThat(export.favorites().get(0).targetType()).isEqualTo("TRIP");
        assertThat(export.generatedAt()).isNotNull();
    }

    @Test
    @DisplayName("kyc est null quand l'utilisateur n'a pas de vérification KYC")
    void kycNullWhenNoVerification() {
        UUID userId = UUID.randomUUID();
        UserEntity user = makeUser(userId);
        when(recipientRepository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
        when(pickupAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId)).thenReturn(List.of());
        when(deliveryAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId)).thenReturn(List.of());
        when(favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.empty());

        UserDataExportDto export = service().export(user);

        assertThat(export.kyc()).isNull();
        assertThat(export.recipients()).isEmpty();
    }
}
