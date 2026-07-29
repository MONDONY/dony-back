package com.yadony.api.addressbook.recipient;

import com.yadony.api.auth.events.UserFinalizedEvent;
import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * Les destinataires enregistrés par un expéditeur contiennent des données
 * personnelles d'un tiers (nom, téléphone). Une fois le compte de
 * l'expéditeur finalisé (RGPD), plus aucune livraison ne sera organisée
 * depuis ce compte — on soft-delete donc son carnet de destinataires avec lui.
 */
@Component
public class RecipientFinalizationListener {

    private static final Logger log = LoggerFactory.getLogger(RecipientFinalizationListener.class);

    private final RecipientRepository recipientRepository;
    private final AuditService auditService;

    public RecipientFinalizationListener(RecipientRepository recipientRepository, AuditService auditService) {
        this.recipientRepository = recipientRepository;
        this.auditService = auditService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserFinalized(UserFinalizedEvent event) {
        List<RecipientEntity> recipients =
                recipientRepository.findByUserIdOrderByUpdatedAtDesc(event.getUserId());
        if (recipients.isEmpty()) {
            return;
        }
        recipients.forEach(RecipientEntity::softDelete);
        recipientRepository.saveAll(recipients);

        auditService.log("RECIPIENT", event.getUserId(), "RECIPIENT_GDPR_BULK_DELETE", event.getUserId(),
                Map.of("count", recipients.size(), "reason", event.getReason().name()));
        log.info("Soft-deleted {} recipient(s) for finalized user {}", recipients.size(), event.getUserId());
    }
}
