package com.healthplatform.emr.storage;

import com.healthplatform.emr.config.MinioProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the MinIO client: object keys are namespaced by encounter and document id
 * so the bucket layout stays browsable, and reads never go through emr-service itself — callers
 * get a short-lived presigned URL and fetch the bytes directly from object storage.
 */
@Component
public class DocumentStorageClient {

    private static final int PRESIGNED_URL_EXPIRY_MINUTES = 15;

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public DocumentStorageClient(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public String store(UUID encounterId, String filename, String contentType, InputStream data, long size) {
        String objectKey = "encounters/%s/%s-%s".formatted(encounterId, UUID.randomUUID(), sanitize(filename));
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(data, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new DocumentStorageException("Failed to store object " + objectKey, e);
        }
        return objectKey;
    }

    public String presignedGetUrl(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .expiry(PRESIGNED_URL_EXPIRY_MINUTES, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            throw new DocumentStorageException("Failed to presign URL for " + objectKey, e);
        }
    }

    private String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "document";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static class DocumentStorageException extends RuntimeException {
        public DocumentStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
