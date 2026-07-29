package com.yadony.api.admin.export;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminExportControllerTest {

    @Mock AdminExportService exportService;
    @Mock AuditService auditService;

    private AdminExportController controller() {
        return new AdminExportController(exportService, auditService);
    }

    @Test
    void download_transactions_returnsCsvAttachment() {
        when(exportService.exportTransactions(any(), any()))
                .thenReturn("header\n".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<byte[]> resp = controller().download("transactions",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("transactions_2026-01-01_2026-01-31.csv");
        verify(auditService).log(eq("EXPORT"), eq(null), eq("EXPORT_RUN"), eq(null), any());
    }

    @Test
    void download_eachValidType_routesToRightService() {
        when(exportService.exportUsers(any(), any())).thenReturn(new byte[0]);
        when(exportService.exportDisputes(any(), any())).thenReturn(new byte[0]);
        when(exportService.exportPayouts(any(), any())).thenReturn(new byte[0]);

        controller().download("users", null, null);
        controller().download("disputes", null, null);
        controller().download("payouts", null, null);

        verify(exportService).exportUsers(null, null);
        verify(exportService).exportDisputes(null, null);
        verify(exportService).exportPayouts(null, null);
    }

    @Test
    void download_invalidType_throws400_andDoesNotAudit() {
        assertThatThrownBy(() -> controller().download("secrets", null, null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void download_withoutDates_filenameHasNoRange() {
        when(exportService.exportUsers(any(), any())).thenReturn(new byte[0]);

        ResponseEntity<byte[]> resp = controller().download("users", null, null);

        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("users.csv");
    }
}
