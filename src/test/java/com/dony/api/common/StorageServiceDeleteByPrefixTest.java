package com.dony.api.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageService.deleteByPrefix")
class StorageServiceDeleteByPrefixTest {

    @Mock private S3Client s3Client;
    @Mock private S3Presigner s3Presigner;

    private StorageService storageService;

    @BeforeEach
    void setUp() throws Exception {
        storageService = new StorageService(s3Client, s3Presigner);
        var field = StorageService.class.getDeclaredField("bucket");
        field.setAccessible(true);
        field.set(storageService, "test-bucket");
    }

    @Test
    @DisplayName("supprime tous les objets sous le préfixe")
    void deletesAllObjectsUnderPrefix() {
        S3Object obj1 = S3Object.builder().key("kyc/user1/photo1.jpg").build();
        S3Object obj2 = S3Object.builder().key("kyc/user1/photo2.jpg").build();
        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(List.of(obj1, obj2)).build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);
        doReturn(DeleteObjectsResponse.builder().build())
                .when(s3Client).deleteObjects(any(DeleteObjectsRequest.class));

        storageService.deleteByPrefix("kyc/user1/");

        ArgumentCaptor<DeleteObjectsRequest> captor =
                ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3Client).deleteObjects(captor.capture());
        List<String> keys = captor.getValue().delete().objects()
                .stream().map(ObjectIdentifier::key).toList();
        assertThat(keys).containsExactlyInAnyOrder("kyc/user1/photo1.jpg", "kyc/user1/photo2.jpg");
    }

    @Test
    @DisplayName("préfixe sans objets → deleteObjects jamais appelé")
    void emptyPrefix_doesNotCallDelete() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).build());

        storageService.deleteByPrefix("kyc/nobody/");

        verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }
}
