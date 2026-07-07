package com.dony.api.export;

import com.dony.api.addressbook.delivery.DeliveryAddressEntity;
import com.dony.api.addressbook.delivery.DeliveryAddressRepository;
import com.dony.api.addressbook.delivery.dto.DeliveryAddressDto;
import com.dony.api.addressbook.pickup.PickupAddressEntity;
import com.dony.api.addressbook.pickup.PickupAddressRepository;
import com.dony.api.addressbook.pickup.dto.PickupAddressDto;
import com.dony.api.addressbook.recipient.RecipientEntity;
import com.dony.api.addressbook.recipient.RecipientRepository;
import com.dony.api.addressbook.recipient.dto.RecipientDto;
import com.dony.api.auth.UserEntity;
import com.dony.api.export.dto.UserDataExportDto;
import com.dony.api.export.dto.UserDataExportDto.FavoriteExport;
import com.dony.api.export.dto.UserDataExportDto.KycExport;
import com.dony.api.export.dto.UserDataExportDto.ProfileExport;
import com.dony.api.favorites.FavoriteRepository;
import com.dony.api.kyc.KycRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserDataExportService {

    private final RecipientRepository recipientRepository;
    private final PickupAddressRepository pickupAddressRepository;
    private final DeliveryAddressRepository deliveryAddressRepository;
    private final FavoriteRepository favoriteRepository;
    private final KycRepository kycRepository;

    public UserDataExportService(RecipientRepository recipientRepository,
                                  PickupAddressRepository pickupAddressRepository,
                                  DeliveryAddressRepository deliveryAddressRepository,
                                  FavoriteRepository favoriteRepository,
                                  KycRepository kycRepository) {
        this.recipientRepository = recipientRepository;
        this.pickupAddressRepository = pickupAddressRepository;
        this.deliveryAddressRepository = deliveryAddressRepository;
        this.favoriteRepository = favoriteRepository;
        this.kycRepository = kycRepository;
    }

    public UserDataExportDto export(UserEntity user) {
        ProfileExport profile = new ProfileExport(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getBirthDate(),
                user.getCity(),
                user.getCountry(),
                user.getBio(),
                user.getAvatarUrl(),
                user.getRoles().stream().map(Enum::name).collect(Collectors.toList()),
                user.getCreatedAt()
        );

        KycExport kyc = kycRepository.findByUserId(user.getId())
                .map(k -> new KycExport(k.getStatus().name(), k.getRejectionReason()))
                .orElse(null);

        List<RecipientDto> recipients = recipientRepository.findByUserIdOrderByUpdatedAtDesc(user.getId())
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        List<PickupAddressDto> pickupAddresses = pickupAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(user.getId())
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        List<DeliveryAddressDto> deliveryAddresses = deliveryAddressRepository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(user.getId())
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        List<FavoriteExport> favorites = favoriteRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(f -> new FavoriteExport(f.getTargetType().name(), f.getTargetId(), f.getCreatedAt()))
                .collect(Collectors.toList());

        return new UserDataExportDto(
                profile,
                kyc,
                recipients,
                pickupAddresses,
                deliveryAddresses,
                favorites,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    private RecipientDto toDto(RecipientEntity e) {
        return new RecipientDto(
                e.getId(),
                e.getFullName(),
                e.getRelationship(),
                e.getPhoneE164(),
                e.getWhatsappE164(),
                e.getStreet(),
                e.getCity(),
                e.getCountry(),
                e.getNotes(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private PickupAddressDto toDto(PickupAddressEntity e) {
        return new PickupAddressDto(
                e.getId(),
                e.getLabel(),
                e.getStreet(),
                e.getPostalCode(),
                e.getCity(),
                e.getCountry(),
                e.getFloorApartment(),
                e.getInstructions(),
                e.getLatitude(),
                e.getLongitude(),
                e.isDefault(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private DeliveryAddressDto toDto(DeliveryAddressEntity e) {
        return new DeliveryAddressDto(
                e.getId(),
                e.getLabel(),
                e.getStreet(),
                e.getCity(),
                e.getCountry(),
                e.getInstructions(),
                e.getLatitude(),
                e.getLongitude(),
                e.isDefault(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
