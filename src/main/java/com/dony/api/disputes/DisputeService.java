package com.dony.api.disputes;

import com.dony.api.common.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class DisputeService {

    private static final String TYPE_NO_SHOW = "SENDER_NO_SHOW_CONTESTED";
    private static final String STATUS_OPEN  = "OPEN";

    private final DisputeRepository disputeRepository;
    private final AuditService auditService;

    public DisputeService(DisputeRepository disputeRepository, AuditService auditService) {
        this.disputeRepository = disputeRepository;
        this.auditService = auditService;
    }

    public DisputeEntity openSenderNoShowDispute(UUID bidId, UUID senderId, UUID travelerId) {
        Optional<DisputeEntity> existing = disputeRepository.findByBidId(bidId);
        if (existing.isPresent()) {
            return existing.get();
        }

        DisputeEntity dispute = new DisputeEntity();
        dispute.setBidId(bidId);
        dispute.setSenderId(senderId);
        dispute.setTravelerId(travelerId);
        dispute.setType(TYPE_NO_SHOW);
        dispute.setStatus(STATUS_OPEN);
        dispute.setRefundFrozen(true);

        DisputeEntity saved = disputeRepository.save(dispute);

        auditService.log("DISPUTE", saved.getId(), "SENDER_NO_SHOW_DISPUTE_OPENED", senderId,
                Map.of("bidId", bidId.toString(), "travelerId", travelerId.toString(),
                       "type", TYPE_NO_SHOW));

        return saved;
    }
}
