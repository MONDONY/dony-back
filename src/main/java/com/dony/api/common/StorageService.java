package com.dony.api.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class StorageService {

    private static final Set<String> ALLOWED_PREFIXES = Set.of("tracking/", "users/");
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp");

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucket;

    public StorageService(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    /**
     * Upload a file to S3 under the given prefix.
     * Allowed prefixes: "tracking/" and "users/"
     *
     * @return the S3 object key
     */
    public String uploadFile(MultipartFile file, String prefix) throws IOException {
        validateFile(file);
        validatePrefix(prefix);

        String key = prefix + Instant.now().toEpochMilli() + "_" + UUID.randomUUID() + getExtension(file);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));

        return key;
    }

    /**
     * Generate a presigned URL for temporary read access (default: 1 hour).
     */
    public String generatePresignedUrl(String objectKey, Duration expiry) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build())
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Delete a file from S3 (RGPD deletion).
     */
    public void deleteFile(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build());
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new DonyBusinessException(HttpStatus.BAD_REQUEST,
                    "FILE_EMPTY", "Empty File", "File is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "FILE_TOO_LARGE", "File Too Large", "File exceeds 10MB limit");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new DonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_FILE_TYPE", "Invalid File Type", "Only JPEG, PNG and WebP images are allowed");
        }
    }

    private void validatePrefix(String prefix) {
        boolean valid = ALLOWED_PREFIXES.stream().anyMatch(prefix::startsWith);
        if (!valid) {
            throw new DonyBusinessException(HttpStatus.BAD_REQUEST,
                    "INVALID_PREFIX", "Invalid Prefix", "Invalid upload destination");
        }
    }

    private String getExtension(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) return ".jpg";
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
