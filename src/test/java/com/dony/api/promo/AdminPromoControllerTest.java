package com.dony.api.promo;

import com.dony.api.admin.AdminPromoController;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.promo.dto.CreatePromoRequest;
import com.dony.api.promo.dto.UpdatePromoStatusRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminPromoControllerTest {

    @Mock PromoCodeRepository promoCodeRepository;
    @Mock AuditService auditService;

    private AdminPromoController controller() {
        return new AdminPromoController(promoCodeRepository, auditService);
    }

    private PromoCodeEntity savedPromo(String code, BigDecimal rate) {
        PromoCodeEntity p = new PromoCodeEntity();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setCode(code);
        p.setRate(rate);
        p.setTarget(PromoCodeTarget.ANY);
        p.setStatus(PromoCodeStatus.ACTIVE);
        p.setPerUserLimit(1);
        return p;
    }

    @Test
    @DisplayName("create: code unique → 201 + entity sauvegardée")
    void create_newCode_returns201() {
        when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.empty());
        PromoCodeEntity saved = savedPromo("WELCOME10", new BigDecimal("0.06"));
        when(promoCodeRepository.save(any())).thenReturn(saved);

        var req = new CreatePromoRequest("WELCOME10", new BigDecimal("0.06"), PromoCodeTarget.ANY,
                null, null, null, null);
        var resp = controller().create(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().code()).isEqualTo("WELCOME10");
        assertThat(resp.getBody().rate()).isEqualByComparingTo("0.06");
        verify(promoCodeRepository).save(any(PromoCodeEntity.class));
    }

    @Test
    @DisplayName("create: code déjà existant → 409 promo-code-exists")
    void create_duplicateCode_throws409() {
        PromoCodeEntity existing = savedPromo("WELCOME10", new BigDecimal("0.06"));
        when(promoCodeRepository.findByCode("WELCOME10")).thenReturn(Optional.of(existing));

        var req = new CreatePromoRequest("WELCOME10", new BigDecimal("0.06"), null, null, null, null, null);

        assertThatThrownBy(() -> controller().create(req))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode()).isEqualTo("promo-code-exists"));
    }

    @Test
    @DisplayName("list: retourne toutes les entités")
    void list_returnsAll() {
        when(promoCodeRepository.findAll()).thenReturn(List.of(
                savedPromo("A", new BigDecimal("0.06")),
                savedPromo("B", new BigDecimal("0.08"))
        ));

        var list = controller().list();
        assertThat(list).hasSize(2);
    }

    @Test
    @DisplayName("updateStatus: DISABLED → enregistré")
    void updateStatus_disabled() {
        PromoCodeEntity p = savedPromo("WELCOME10", new BigDecimal("0.06"));
        UUID id = (UUID) ReflectionTestUtils.getField(p, "id");
        when(promoCodeRepository.findById(id)).thenReturn(Optional.of(p));
        when(promoCodeRepository.save(p)).thenReturn(p);

        var resp = controller().updateStatus(id, new UpdatePromoStatusRequest(PromoCodeStatus.DISABLED));

        assertThat(resp.getBody().status()).isEqualTo(PromoCodeStatus.DISABLED);
    }

    @Test
    @DisplayName("delete: soft delete + audit")
    void delete_softDeletes() {
        PromoCodeEntity p = savedPromo("WELCOME10", new BigDecimal("0.06"));
        UUID id = (UUID) ReflectionTestUtils.getField(p, "id");
        when(promoCodeRepository.findById(id)).thenReturn(Optional.of(p));
        when(promoCodeRepository.save(p)).thenReturn(p);

        controller().delete(id);

        assertThat(p.getDeletedAt()).isNotNull();
        verify(auditService).log(eq("PROMO"), eq(id), eq("PROMO_CODE_DELETED"), any(), any());
    }

    @Test
    @DisplayName("DTO: taux négatif rejeté par bean validation")
    void createPromoRequest_negativeRate_isRejected() {
        try (var f = Validation.buildDefaultValidatorFactory()) {
            Validator v = f.getValidator();
            var req = new CreatePromoRequest("X", new BigDecimal("-0.01"), null, null, null, null, null);
            assertThat(v.validate(req)).isNotEmpty();
        }
    }
}
