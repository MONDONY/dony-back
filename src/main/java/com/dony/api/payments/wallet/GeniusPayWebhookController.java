package com.dony.api.payments.wallet;

import com.dony.api.common.DonyBusinessException;
import com.dony.api.common.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Webhook de confirmation de recharge wallet GeniusPay. Ne concerne QUE la
 * recharge du wallet interne du voyageur (jamais le prix du transport).
 */
@RestController
public class GeniusPayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GeniusPayWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GeniusPaySignatureVerifier signatureVerifier;
    private final ProcessedGeniusPayEventRepository processedEventRepository;
    private final WalletTopupRequestRepository topupRequestRepository;
    private final WalletService walletService;
    private final AuditService auditService;

    public GeniusPayWebhookController(GeniusPaySignatureVerifier signatureVerifier,
                                      ProcessedGeniusPayEventRepository processedEventRepository,
                                      WalletTopupRequestRepository topupRequestRepository,
                                      WalletService walletService,
                                      AuditService auditService) {
        this.signatureVerifier = signatureVerifier;
        this.processedEventRepository = processedEventRepository;
        this.topupRequestRepository = topupRequestRepository;
        this.walletService = walletService;
        this.auditService = auditService;
    }

    @PostMapping("/webhooks/genius-pay")
    @Transactional
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "X-GeniusPay-Signature", required = false) String signature,
            @RequestBody String rawPayload) {

        if (!signatureVerifier.verify(rawPayload, signature)) {
            throw new DonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "invalid-geniuspay-signature", "Invalid Signature",
                    "Signature webhook GeniusPay invalide");
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(rawPayload);
        } catch (Exception e) {
            log.warn("GeniusPay webhook: payload JSON invalide");
            return ResponseEntity.ok().build();
        }

        String event = node.path("event").asText(null);
        String reference = node.path("data").path("transaction").path("reference").asText(null);
        if (reference == null) {
            log.warn("GeniusPay webhook: aucune référence extraite, event={}", event);
            return ResponseEntity.ok().build();
        }

        // Anti-rejeu : check-then-act (même principe que StripeWebhookIngestService.ingest()).
        if (processedEventRepository.existsById(reference)) {
            log.info("GeniusPay webhook: référence {} déjà traitée (rejeu), no-op", reference);
            return ResponseEntity.ok().build();
        }
        ProcessedGeniusPayEventEntity processed = new ProcessedGeniusPayEventEntity();
        processed.setExternalReference(reference);
        processed.setProcessedAt(LocalDateTime.now(ZoneOffset.UTC));
        processedEventRepository.save(processed);

        var topupOpt = topupRequestRepository.findByExternalReference(reference);
        if (topupOpt.isEmpty()) {
            log.warn("GeniusPay webhook: aucune WalletTopupRequestEntity pour reference={}", reference);
            return ResponseEntity.ok().build();
        }
        WalletTopupRequestEntity topup = topupOpt.get();
        topup.setWebhookReceivedAt(LocalDateTime.now(ZoneOffset.UTC));

        if ("payment.success".equals(event)) {
            topup.setStatus("COMPLETED");
            topupRequestRepository.save(topup);
            walletService.credit(topup.getUserId(), topup.getAmountEur(),
                    WalletTransactionType.TOP_UP, reference, "geniuspay-" + reference);
            auditService.log("WALLET_TOPUP", topup.getUserId(), "GENIUSPAY_TOPUP_COMPLETED",
                    topup.getUserId(), java.util.Map.of(
                            "reference", reference,
                            "amountEur", topup.getAmountEur().toPlainString(),
                            "currency", topup.getCurrency()));
        } else if ("payment.failed".equals(event) || "payment.cancelled".equals(event)) {
            topup.setStatus("FAILED");
            topup.setFailureReason(event);
            topupRequestRepository.save(topup);
        }

        return ResponseEntity.ok().build();
    }
}
