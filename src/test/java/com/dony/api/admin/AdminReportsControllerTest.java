package com.dony.api.admin;

import com.dony.api.admin.dto.AdminReportResponse;
import com.dony.api.admin.dto.ResolveReportRequest;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.AuditService;
import com.dony.api.common.DonyBusinessException;
import com.dony.api.signalements.ReportEntity;
import com.dony.api.signalements.ReportRepository;
import com.dony.api.signalements.ReportStatus;
import com.dony.api.signalements.ReportTargetType;
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


import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminReportsControllerTest {

    @Mock ReportRepository reportRepo;
    @Mock UserRepository userRepo;
    @Mock AuditService auditService;

    private AdminReportsController controller() {
        return new AdminReportsController(reportRepo, userRepo, auditService);
    }

    // ---- listReports ----

    @Test
    void listReports_noFilter_returnsEmptyPage() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(null, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTotalElements()).isEqualTo(0);
    }

    @Test
    void listReports_withStatusFilter_passesFilterToRepository() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.findFiltered(eq(ReportStatus.OPEN), isNull(), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(ReportStatus.OPEN, null, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reportRepo).findFiltered(eq(ReportStatus.OPEN), isNull(), any(Pageable.class));
    }

    @Test
    void listReports_withTargetTypeFilter_passesFilterToRepository() {
        Page<ReportEntity> page = new PageImpl<>(List.of());
        when(reportRepo.findFiltered(isNull(), eq(ReportTargetType.USER), any(Pageable.class))).thenReturn(page);

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(null, ReportTargetType.USER, 0, 20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(reportRepo).findFiltered(isNull(), eq(ReportTargetType.USER), any(Pageable.class));
    }

    @Test
    void listReports_enrichesReporterName() {
        UUID reporterId = UUID.randomUUID();
        ReportEntity report = buildReport(reporterId, ReportStatus.OPEN);

        UserEntity reporter = new UserEntity();
        reporter.setFirstName("Jean");
        reporter.setLastName("Dupont");

        ReflectionTestUtils.setField(reporter, "id", reporterId);
        Page<ReportEntity> page = new PageImpl<>(List.of(report));
        when(reportRepo.findFiltered(isNull(), isNull(), any(Pageable.class))).thenReturn(page);
        when(userRepo.findAllById(anyCollection())).thenReturn(List.of(reporter));

        ResponseEntity<Page<AdminReportResponse>> resp =
                controller().listReports(null, null, 0, 20);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getContent()).hasSize(1);
        assertThat(resp.getBody().getContent().get(0).reporterName()).isEqualTo("Jean Dupont");
    }

    // ---- resolveReport ----

    @Test
    void resolveReport_setsResolvedStatusAndAudits() {
        UUID id = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        ReportEntity report = buildReport(reporterId, ReportStatus.OPEN);

        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(reportRepo.save(report)).thenReturn(report);

        ResolveReportRequest request = new ResolveReportRequest("WARN_USER", "Contenu inapproprié");
        ResponseEntity<AdminReportResponse> resp = controller().resolveReport(id, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(report.getActionTaken()).isEqualTo("WARN_USER");
        assertThat(report.getResolutionNote()).isEqualTo("Contenu inapproprié");
        assertThat(report.getResolvedAt()).isNotNull();
        verify(reportRepo).save(report);
        verify(auditService).log(eq("REPORT"), eq(id), eq("REPORT_RESOLVED"), isNull(), anyMap());
    }

    @Test
    void resolveReport_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(reportRepo.findById(id)).thenReturn(Optional.empty());

        DonyBusinessException ex = assertThrows(DonyBusinessException.class,
                () -> controller().resolveReport(id, new ResolveReportRequest("ACTION", "note")));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void resolveReport_responseContainsStatusAndAction() {
        UUID id = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        ReportEntity report = buildReport(reporterId, ReportStatus.OPEN);

        when(reportRepo.findById(id)).thenReturn(Optional.of(report));
        when(reportRepo.save(report)).thenReturn(report);

        ResolveReportRequest request = new ResolveReportRequest("BAN_USER", "Récidiviste");
        ResponseEntity<AdminReportResponse> resp = controller().resolveReport(id, request);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo("RESOLVED");
        assertThat(resp.getBody().actionTaken()).isEqualTo("BAN_USER");
        assertThat(resp.getBody().resolutionNote()).isEqualTo("Récidiviste");
    }

    // ---- helpers ----

    private ReportEntity buildReport(UUID reporterId, ReportStatus status) {
        ReportEntity r = new ReportEntity();
        r.setTargetType(ReportTargetType.USER);
        r.setTargetId(UUID.randomUUID());
        r.setReporterId(reporterId);
        r.setReason("spam");
        r.setDescription("Envoi de spam répété");
        r.setStatus(status);
        return r;
    }
}
