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
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) String timestamp,
            @RequestBody String rawPayload) {

        if (!signatureVerifier.verify(rawPayload, signature, timestamp)) {
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
        // Référence directement sur data.reference (pas de wrapper "transaction" —
        // confirmé par la doc officielle GeniusPay, guide webhook 2026-07-19).
        String reference = node.path("data").path("reference").asText(null);
        if (reference == null) {
            log.warn("GeniusPay webhook: aucune référence extraite, event={}", event);
            return ResponseEntity.ok().build();
        }

        // Anti-rejeu : check-then-act (même principe que StripeWebhookIngestService.ingest()).
        // Clé composite "event:reference" (et non reference seule) : le contrat exact de GeniusPay
        // n'est pas vérifiable depuis le code (webhook terminal unique vs plusieurs events pour la
        // même référence, ex. payment.pending puis payment.success). Avec reference seule, un premier
        // webhook non-terminal marquerait la référence comme "traitée" et bloquerait silencieusement
        // le payment.success qui doit créditer le wallet. La clé composite garde la même colonne
        // external_reference (VARCHAR(255), pas de migration nécessaire) mais y stocke "event:reference"
        // pour que deux events différents sur la même référence restent distincts, tout en bloquant
        // toujours un rejeu exact (même event + même référence).
        String dedupKey = event + ":" + reference;
        if (processedEventRepository.existsById(dedupKey)) {
            log.info("GeniusPay webhook: event {} pour référence {} déjà traité (rejeu), no-op", event, reference);
            return ResponseEntity.ok().build();
        }
        ProcessedGeniusPayEventEntity processed = new ProcessedGeniusPayEventEntity();
        processed.setExternalReference(dedupKey);
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
            // Défense en profondeur : même si la clé de dédup ci-dessus laissait passer un doublon
            // (ex. libellés d'event légèrement différents entre deux webhooks GeniusPay pour le même
            // succès), on ne recrédite jamais un topup qui n'est plus PENDING.
            if (!"PENDING".equals(topup.getStatus())) {
                log.info("GeniusPay webhook: topup {} déjà au statut {} (≠ PENDING), pas de nouveau crédit",
                        topup.getId(), topup.getStatus());
                return ResponseEntity.ok().build();
            }
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
