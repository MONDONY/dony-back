package com.dony.api.addressbook.recipient;

import com.dony.api.addressbook.recipient.dto.CreateRecipientRequest;
import com.dony.api.addressbook.recipient.dto.RecipientDto;
import com.dony.api.addressbook.recipient.dto.UpdateRecipientRequest;
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
public class RecipientService {

    private static final Logger log = LoggerFactory.getLogger(RecipientService.class);

    private final RecipientRepository repository;
    private final AuditService auditService;

    public RecipientService(RecipientRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public List<RecipientDto> findAll(UUID userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public RecipientDto create(UUID userId, CreateRecipientRequest request) {
        RecipientEntity entity = new RecipientEntity();
        entity.setUserId(userId);
        entity.setFullName(request.fullName());
        entity.setRelationship(request.relationship());
        entity.setPhoneE164(request.phoneE164());
        entity.setWhatsappE164(request.whatsappE164());
        entity.setStreet(request.street());
        entity.setCity(request.city());
        entity.setCountry(request.country());
        entity.setNotes(request.notes());

        repository.save(entity);

        auditService.log("RECIPIENT", entity.getId(), "RECIPIENT_CREATED", userId,
                Map.of("fullName", request.fullName(), "country", request.country()));

        log.info("Recipient created: id={} userId={}", entity.getId(), userId);
        return toDto(entity);
    }

    @Transactional
    public RecipientDto update(UUID userId, UUID id, UpdateRecipientRequest request) {
        RecipientEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new DonyNotFoundException("Recipient", id));

        entity.setFullName(request.fullName());
        entity.setRelationship(request.relationship());
        entity.setPhoneE164(request.phoneE164());
        entity.setWhatsappE164(request.whatsappE164());
        entity.setStreet(request.street());
        entity.setCity(request.city());
        entity.setCountry(request.country());
        entity.setNotes(request.notes());

        repository.save(entity);

        auditService.log("RECIPIENT", entity.getId(), "RECIPIENT_UPDATED", userId,
                Map.of("fullName", request.fullName()));

        log.info("Recipient updated: id={} userId={}", id, userId);
        return toDto(entity);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        RecipientEntity entity = repository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new DonyNotFoundException("Recipient", id));

        entity.softDelete();
        repository.save(entity);

        auditService.log("RECIPIENT", entity.getId(), "RECIPIENT_DELETED", userId,
                Map.of("id", id.toString()));

        log.info("Recipient soft-deleted: id={} userId={}", id, userId);
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
}
