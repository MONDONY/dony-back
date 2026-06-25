package com.dony.api.admin;

import com.dony.api.admin.dto.AdminCancellationResponse;
import com.dony.api.admin.dto.AdminDisputeDetailResponse;
import com.dony.api.admin.dto.AdminDisputeListItemResponse;
import com.dony.api.admin.dto.AdminGuaranteeFundRequest;
import com.dony.api.admin.dto.AdminResolveDisputeRequest;
import com.dony.api.auth.UserRepository;
import com.dony.api.cancellation.CancellationEntity;
import com.dony.api.cancellation.CancellationRepository;
import com.dony.api.cancellation.CancellationStatus;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.disputes.DisputeEntity;
import com.dony.api.disputes.DisputeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminDisputesControllerTest {

    @Mock DisputeRepository disputeRepo;
    @Mock CancellationRepository cancellationRepo;
    @Mock AuditService auditService;
    @Mock UserRepository userRepo;

    private AdminDisputesController controller() {
        return new AdminDisputesController(disputeRepo, cancellationRepo, auditService, userRepo);
    }

    // ---- listDisputes ----

    @Test
    void listDisputes_noFilter_returnsPage() {
        Page<DisputeEntity> page = new PageImpl<>(List.of());
        when(disputeRepo.findAdminFiltered(isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminDisputeListItemResponse>> resp =
                controller().listDisputes(null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTotalElements()).isEqualTo(0);
    }

    @Test
    void listDisputes_withStatusFilter_passesStatus() {
        Page<DisputeEntity> page = new PageImpl<>(List.of());
        when(disputeRepo.findAdminFiltered(eq("OPEN"), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminDisputeListItemResponse>> resp =
                controller().listDisputes("OPEN", 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(disputeRepo).findAdminFiltered(eq("OPEN"), any(Pageable.class));
    }

    // ---- getDispute ----

    @Test
    void getDispute_found_returnsDetail() {
        UUID id = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));

        ResponseEntity<AdminDisputeDetailResponse> resp = controller().getDispute(id);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo("OPEN");
    }

    @Test
    void getDispute_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(disputeRepo.findById(id)).thenReturn(Optional.empty());

        assertThrows(DonyBusinessException.class, () -> controller().getDispute(id));
    }

    // ---- resolveDispute ----

    @Test
    void resolveDispute_setsFieldsAndReturns200() {
        UUID id = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));
        when(disputeRepo.save(entity)).thenReturn(entity);

        AdminResolveDisputeRequest request = new AdminResolveDisputeRequest("REFUND_SENDER", "note");
        ResponseEntity<AdminDisputeDetailResponse> resp = controller().resolveDispute(id, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getStatus()).isEqualTo("RESOLVED");
        assertThat(entity.getResolutionType()).isEqualTo("REFUND_SENDER");
        assertThat(entity.getResolutionNote()).isEqualTo("note");
        assertThat(entity.getResolvedAt()).isNotNull();
        verify(disputeRepo).save(entity);
        verify(auditService).log(eq("DISPUTE"), eq(entity.getId()), eq("RESOLVE"), isNull(), any());
    }

    @Test
    void resolveDispute_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(disputeRepo.findById(id)).thenReturn(Optional.empty());

        assertThrows(DonyBusinessException.class,
                () -> controller().resolveDispute(id, new AdminResolveDisputeRequest("res", "note")));
    }

    // ---- payGuaranteeFund ----

    @Test
    void payGuaranteeFund_setsFieldsAndReturns200() {
        UUID id = UUID.randomUUID();
        UUID beneficiary = UUID.randomUUID();
        DisputeEntity entity = new DisputeEntity();
        entity.setStatus("OPEN");
        when(disputeRepo.findById(id)).thenReturn(Optional.of(entity));
        when(disputeRepo.save(entity)).thenReturn(entity);

        AdminGuaranteeFundRequest request = new AdminGuaranteeFundRequest(5000, beneficiary, "paiement fonds de garantie");
        ResponseEntity<AdminDisputeDetailResponse> resp = controller().payGuaranteeFund(id, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getStatus()).isEqualTo("RESOLVED");
        assertThat(entity.getResolutionType()).isEqualTo("GUARANTEE_PAID");
        assertThat(entity.getBeneficiaryUserId()).isEqualTo(beneficiary);
        assertThat(entity.getResolvedAt()).isNotNull();
        verify(disputeRepo).save(entity);
        verify(auditService).log(eq("DISPUTE"), eq(entity.getId()), eq("GUARANTEE_FUND"), isNull(), any());
    }

    @Test
    void payGuaranteeFund_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(disputeRepo.findById(id)).thenReturn(Optional.empty());

        assertThrows(DonyBusinessException.class,
                () -> controller().payGuaranteeFund(id,
                        new AdminGuaranteeFundRequest(5000, UUID.randomUUID(), "reason")));
    }

    // ---- listCancellations ----

    @Test
    void listCancellations_noFilter_returnsPage() {
        Page<CancellationEntity> page = new PageImpl<>(List.of());
        when(cancellationRepo.findAdminFiltered(isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminCancellationResponse>> resp =
                controller().listCancellations(null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void listCancellations_withStatusFilter_passesEnum() {
        Page<CancellationEntity> page = new PageImpl<>(List.of());
        when(cancellationRepo.findAdminFiltered(eq(CancellationStatus.CONTESTED), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<Page<AdminCancellationResponse>> resp =
                controller().listCancellations(CancellationStatus.CONTESTED, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cancellationRepo).findAdminFiltered(eq(CancellationStatus.CONTESTED), any(Pageable.class));
    }

    @Test
    void listCancellations_withNullStatus_returnsOk() {
        Page<CancellationEntity> page = new PageImpl<>(List.of());
        when(cancellationRepo.findAdminFiltered(eq(null), any(Pageable.class)))
                .thenReturn(page);

        ResponseEntity<Page<AdminCancellationResponse>> resp =
                controller().listCancellations(null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cancellationRepo).findAdminFiltered(eq(null), any(Pageable.class));
    }
}
