package com.yadony.api.signalements;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock ReportRepository reportRepository;
    @Mock ReportPhotoRepository photoRepository;
    @Mock UserRepository userRepository;
    @Mock StorageService storageService;
    @Mock AuditService auditService;

    private UserEntity reporter;
    private UUID reporterId;

    private ReportService service() {
        return new ReportService(reportRepository, photoRepository, userRepository, storageService, auditService);
    }

    @BeforeEach
    void setUp() throws Exception {
        reporterId = UUID.randomUUID();
        reporter = new UserEntity();
        setId(reporter, reporterId);
    }

    private static void setId(Object entity, UUID id) throws Exception {
        Field f = entity.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }

    private void stubUser() {
        when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(reporter));
    }

    // ── uploadPhoto ────────────────────────────────────────────────────────────

    @Test
    void uploadPhoto_storesUnderReporterPrefix() throws IOException {
        stubUser();
        MockMultipartFile file = new MockMultipartFile("file", "s.png", "image/png", new byte[]{1});
        when(storageService.uploadFile(eq(file), eq("reports/" + reporterId + "/")))
                .thenReturn("reports/" + reporterId + "/123_s.png");

        String key = service().uploadPhoto("uid-1", file);

        assertThat(key).startsWith("reports/" + reporterId + "/");
    }

    @Test
    void uploadPhoto_ioError_throws422() throws IOException {
        stubUser();
        MockMultipartFile file = new MockMultipartFile("file", "s.png", "image/png", new byte[]{1});
        when(storageService.uploadFile(any(), anyString())).thenThrow(new IOException("disk"));

        assertThatThrownBy(() -> service().uploadPhoto("uid-1", file))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void uploadPhoto_unknownUser_throws401() {
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("file", "s.png", "image/png", new byte[]{1});

        assertThatThrownBy(() -> service().uploadPhoto("ghost", file))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── createReport ───────────────────────────────────────────────────────────

    @Test
    void createReport_appIncident_withoutTargetId_ok() throws Exception {
        stubUser();
        when(reportRepository.save(any())).thenAnswer(inv -> {
            ReportEntity r = inv.getArgument(0);
            setId(r, UUID.randomUUID());
            return r;
        });

        ReportEntity report = service().createReport("uid-1", ReportTargetType.APP, null,
                "Bug paiement", "L'écran de paiement crash", List.of());

        assertThat(report.getStatus()).isEqualTo(ReportStatus.OPEN);
        assertThat(report.getReporterId()).isEqualTo(reporterId);
        verify(auditService).log(eq("REPORT"), any(), eq("REPORT_CREATED"), eq(reporterId), any());
    }

    @Test
    void createReport_nonApp_withoutTargetId_throws400() {
        stubUser();
        assertThatThrownBy(() -> service().createReport("uid-1", ReportTargetType.BID, null,
                "Colis endommagé", null, List.of()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createReport_blankReason_throws400() {
        stubUser();
        assertThatThrownBy(() -> service().createReport("uid-1", ReportTargetType.APP, null,
                "  ", null, List.of()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createReport_tooManyPhotos_throws422() {
        stubUser();
        String p = "reports/" + reporterId + "/";
        List<String> five = List.of(p + "1", p + "2", p + "3", p + "4", p + "5");

        assertThatThrownBy(() -> service().createReport("uid-1", ReportTargetType.APP, null,
                "Bug", null, five))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void createReport_photoOfAnotherUser_throws403() {
        stubUser();
        List<String> foreign = List.of("reports/" + UUID.randomUUID() + "/x.png");

        assertThatThrownBy(() -> service().createReport("uid-1", ReportTargetType.APP, null,
                "Bug", null, foreign))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createReport_attachesEachPhoto() throws Exception {
        stubUser();
        when(reportRepository.save(any())).thenAnswer(inv -> {
            ReportEntity r = inv.getArgument(0);
            setId(r, UUID.randomUUID());
            return r;
        });
        String prefix = "reports/" + reporterId + "/";

        service().createReport("uid-1", ReportTargetType.APP, null, "Bug", null,
                List.of(prefix + "a.png", prefix + "b.png"));

        ArgumentCaptor<ReportPhotoEntity> captor = ArgumentCaptor.forClass(ReportPhotoEntity.class);
        verify(photoRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ReportPhotoEntity::getObjectKey)
                .containsExactly(prefix + "a.png", prefix + "b.png");
    }

    // ── photoUrls ──────────────────────────────────────────────────────────────

    @Test
    void photoUrls_presignsEachKey() {
        UUID reportId = UUID.randomUUID();
        ReportPhotoEntity photo = new ReportPhotoEntity();
        photo.setReportId(reportId);
        photo.setObjectKey("reports/u/1.png");
        when(photoRepository.findByReportIdOrderByCreatedAtAsc(reportId)).thenReturn(List.of(photo));
        when(storageService.generatePresignedUrl(eq("reports/u/1.png"), any()))
                .thenReturn("https://s3/presigned/1.png");

        List<String> urls = service().photoUrls(reportId);

        assertThat(urls).containsExactly("https://s3/presigned/1.png");
    }

    @Test
    void photoUrlsByReport_groupsByReport_emptyInputShortCircuits() {
        assertThat(service().photoUrlsByReport(List.of())).isEmpty();
        verify(photoRepository, never()).findByReportIdInOrderByCreatedAtAsc(any());

        UUID r1 = UUID.randomUUID();
        ReportPhotoEntity photo = new ReportPhotoEntity();
        photo.setReportId(r1);
        photo.setObjectKey("k1");
        when(photoRepository.findByReportIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(photo));
        when(storageService.generatePresignedUrl(eq("k1"), any())).thenReturn("u1");

        Map<UUID, List<String>> map = service().photoUrlsByReport(List.of(r1));

        assertThat(map).containsEntry(r1, List.of("u1"));
    }
}
