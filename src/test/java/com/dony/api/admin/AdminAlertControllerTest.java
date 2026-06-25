package com.dony.api.admin;

import com.dony.api.admin.dto.AdminAlertResponse;
import com.dony.api.admin.dto.ResolveAlertRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAlertControllerTest {

    @Mock AdminAlertRepository alertRepo;

    private AdminAlertController controller() {
        return new AdminAlertController(alertRepo);
    }

    @Test
    void list_returnsPage() {
        AdminAlertEntity entity = new AdminAlertEntity();
        Page<AdminAlertEntity> page = new PageImpl<>(List.of(entity));
        when(alertRepo.findFiltered(isNull(), isNull(), eq(false), any())).thenReturn(page);

        ResponseEntity<?> resp = controller().list(null, null, false, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resolve_marksResolvedAndReturns200() {
        UUID id = UUID.randomUUID();
        AdminAlertEntity entity = new AdminAlertEntity();
        when(alertRepo.findById(id)).thenReturn(Optional.of(entity));
        when(alertRepo.save(entity)).thenReturn(entity);

        ResponseEntity<AdminAlertResponse> resp =
            controller().resolve(id, new ResolveAlertRequest("note de résolution"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.isResolved()).isTrue();
        verify(alertRepo).save(entity);
    }

    @Test
    void resolve_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(alertRepo.findById(id)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
            com.dony.api.common.DonyBusinessException.class,
            () -> controller().resolve(id, new ResolveAlertRequest("note"))
        );
    }
}
