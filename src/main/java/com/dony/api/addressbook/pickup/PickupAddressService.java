package com.dony.api.addressbook.pickup;

import com.dony.api.addressbook.pickup.dto.CreatePickupAddressRequest;
import com.dony.api.addressbook.pickup.dto.PickupAddressDto;
import com.dony.api.addressbook.pickup.dto.UpdatePickupAddressRequest;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyNotFoundException;
import com.dony.api.common.DonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PickupAddressService {

    private static final Logger log = LoggerFactory.getLogger(PickupAddressService.class);

    private final PickupAddressRepository repository;
    private final AuditService auditService;

    public PickupAddressService(PickupAddressRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public List<PickupAddressDto> findAll(UUID userId) {
        return repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public PickupAddressDto create(UUID userId, CreatePickupAddressRequest request) {
        if (request.isDefault()) {
            unsetAllDefaults(userId);
        }

        PickupAddressEntity entity = new PickupAddressEntity();
        entity.setUserId(userId);
        entity.setLabel(request.label());
        entity.setStreet(request.street());
        entity.setPostalCode(request.postalCode());
        entity.setCity(request.city());
        entity.setCountry(request.country());
        entity.setFloorApartment(request.floorApartment());
        entity.setInstructions(request.instructions());
        entity.setLatitude(request.latitude());
        entity.setLongitude(request.longitude());
        entity.setDefault(request.isDefault());

        repository.save(entity);

        auditService.log("PICKUP_ADDRESS", entity.getId(), "PICKUP_ADDRESS_CREATED", userId,
                Map.of("label", request.label(), "city", request.city()));

        log.info("PickupAddress created: id={} userId={}", entity.getId(), userId);
        return toDto(entity);
    }

    @Transactional
    public PickupAddressDto update(UUID userId, UUID id, UpdatePickupAddressRequest request) {
        PickupAddressEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new DonyNotFoundException("PickupAddress", id));

        if (request.isDefault() && !entity.isDefault()) {
            unsetAllDefaults(userId);
        }

        entity.setLabel(request.label());
        entity.setStreet(request.street());
        entity.setPostalCode(request.postalCode());
        entity.setCity(request.city());
        entity.setCountry(request.country());
        entity.setFloorApartment(request.floorApartment());
        entity.setInstructions(request.instructions());
        entity.setLatitude(request.latitude());
        entity.setLongitude(request.longitude());
        entity.setDefault(request.isDefault());

        repository.save(entity);

        auditService.log("PICKUP_ADDRESS", entity.getId(), "PICKUP_ADDRESS_UPDATED", userId,
                Map.of("label", request.label()));

        log.info("PickupAddress updated: id={} userId={}", id, userId);
        return toDto(entity);
    }

    @Transactional
    public PickupAddressDto setDefault(UUID userId, UUID id) {
        PickupAddressEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new DonyNotFoundException("PickupAddress", id));

        unsetAllDefaults(userId);
        entity.setDefault(true);
        repository.save(entity);

        auditService.log("PICKUP_ADDRESS", entity.getId(), "PICKUP_ADDRESS_SET_DEFAULT", userId,
                Map.of("id", id.toString()));

        log.info("PickupAddress set as default: id={} userId={}", id, userId);
        return toDto(entity);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        PickupAddressEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new DonyNotFoundException("PickupAddress", id));

        entity.softDelete();
        repository.save(entity);

        auditService.log("PICKUP_ADDRESS", entity.getId(), "PICKUP_ADDRESS_DELETED", userId,
                Map.of("id", id.toString()));

        log.info("PickupAddress soft-deleted: id={} userId={}", id, userId);
    }

    private void unsetAllDefaults(UUID userId) {
        repository.findDefaultByUserId(userId).ifPresent(existing -> {
            existing.setDefault(false);
            repository.save(existing);
        });
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
}
