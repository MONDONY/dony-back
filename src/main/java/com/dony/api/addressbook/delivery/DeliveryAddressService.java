package com.dony.api.addressbook.delivery;

import com.dony.api.addressbook.delivery.dto.*;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DeliveryAddressService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAddressService.class);

    private final DeliveryAddressRepository repository;
    private final AuditService auditService;

    public DeliveryAddressService(DeliveryAddressRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public List<DeliveryAddressDto> findAll(UUID userId) {
        return repository.findByUserIdOrderByIsDefaultDescUpdatedAtDesc(userId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Transactional
    public DeliveryAddressDto create(UUID userId, CreateDeliveryAddressRequest request) {
        if (request.isDefault()) unsetAllDefaults(userId);

        DeliveryAddressEntity entity = new DeliveryAddressEntity();
        entity.setUserId(userId);
        entity.setLabel(request.label());
        entity.setStreet(request.street());
        entity.setCity(request.city());
        entity.setCountry(request.country());
        entity.setInstructions(request.instructions());
        entity.setLatitude(request.latitude());
        entity.setLongitude(request.longitude());
        entity.setDefault(request.isDefault());
        repository.save(entity);

        auditService.log("DELIVERY_ADDRESS", entity.getId(), "DELIVERY_ADDRESS_CREATED", userId,
                Map.of("label", request.label(), "city", request.city()));
        log.info("DeliveryAddress created: id={} userId={}", entity.getId(), userId);
        return toDto(entity);
    }

    @Transactional
    public DeliveryAddressDto update(UUID userId, UUID id, UpdateDeliveryAddressRequest request) {
        DeliveryAddressEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new DonyNotFoundException("DeliveryAddress", id));

        if (request.isDefault() && !entity.isDefault()) unsetAllDefaults(userId);

        entity.setLabel(request.label());
        entity.setStreet(request.street());
        entity.setCity(request.city());
        entity.setCountry(request.country());
        entity.setInstructions(request.instructions());
        entity.setLatitude(request.latitude());
        entity.setLongitude(request.longitude());
        entity.setDefault(request.isDefault());
        repository.save(entity);

        auditService.log("DELIVERY_ADDRESS", entity.getId(), "DELIVERY_ADDRESS_UPDATED", userId,
                Map.of("label", request.label()));
        log.info("DeliveryAddress updated: id={} userId={}", id, userId);
        return toDto(entity);
    }

    @Transactional
    public DeliveryAddressDto setDefault(UUID userId, UUID id) {
        DeliveryAddressEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new DonyNotFoundException("DeliveryAddress", id));
        unsetAllDefaults(userId);
        entity.setDefault(true);
        repository.save(entity);
        auditService.log("DELIVERY_ADDRESS", entity.getId(), "DELIVERY_ADDRESS_SET_DEFAULT", userId,
                Map.of("id", id.toString()));
        return toDto(entity);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        DeliveryAddressEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new DonyNotFoundException("DeliveryAddress", id));
        entity.softDelete();
        repository.save(entity);
        auditService.log("DELIVERY_ADDRESS", entity.getId(), "DELIVERY_ADDRESS_DELETED", userId,
                Map.of("id", id.toString()));
    }

    private void unsetAllDefaults(UUID userId) {
        repository.findDefaultByUserId(userId).ifPresent(e -> {
            e.setDefault(false);
            repository.save(e);
        });
    }

    private DeliveryAddressDto toDto(DeliveryAddressEntity e) {
        return new DeliveryAddressDto(e.getId(), e.getLabel(), e.getStreet(), e.getCity(),
                e.getCountry(), e.getInstructions(), e.getLatitude(), e.getLongitude(),
                e.isDefault(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
