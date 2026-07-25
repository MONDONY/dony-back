package com.dony.api.common;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /** Valeur de remplacement écrite à la place d'une donnée personnelle. */
    static final String REDACTED = "[redacted]";

    public void log(String entityType, UUID entityId, String action, UUID actorId, Map<String, Object> payload) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setAction(action);
        entry.setActorId(actorId);
        // audit_log est IMMUABLE (trigger PostgreSQL) : toute PII écrite dans le
        // payload JSONB y resterait indéfiniment. On masque donc les valeurs
        // personnelles au dernier moment, quel que soit l'appelant. Les IDs,
        // montants, statuts, codes techniques restent (nécessaires à la traçabilité).
        entry.setPayload(redact(payload));
        auditLogRepository.save(entry);
    }

    /**
     * Remplace par {@link #REDACTED} la valeur de toute clé désignant une donnée
     * personnelle (téléphone, email, nom, adresse, GPS, IP, SIRET…). La clé est
     * conservée (on sait qu'une donnée existait) mais jamais sa valeur.
     */
    static Map<String, Object> redact(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return payload;
        }
        Map<String, Object> out = new LinkedHashMap<>(payload.size());
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            out.put(e.getKey(), isSensitiveKey(e.getKey()) ? REDACTED : e.getValue());
        }
        return out;
    }

    /**
     * Denylist volontairement précise pour éviter les faux positifs (ex.
     * {@code recipientId} contient « ip » mais n'est pas une PII).
     */
    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        if (k.endsWith("name")) {                       // fullName, firstName, recipientName…
            return true;
        }
        return k.equals("ip") || k.equals("lat") || k.equals("lng") || k.equals("lon")
                || k.equals("label") || k.equals("city")
                || k.contains("phone") || k.contains("email") || k.contains("whatsapp")
                || k.contains("address") || k.contains("street") || k.contains("postal")
                || k.contains("neighborhood")
                || k.contains("latitude") || k.contains("longitude")
                || k.contains("signedip") || k.contains("clientip") || k.contains("ipaddress")
                || k.contains("siret") || k.contains("birth")
                || k.contains("selfie") || k.contains("iddocument");
    }
}
