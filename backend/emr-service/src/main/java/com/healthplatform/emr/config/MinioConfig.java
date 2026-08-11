package com.healthplatform.emr.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }

    /**
     * Creates the attachments bucket on startup if it doesn't exist yet. Local/dev MinIO starts
     * empty, and there's no infra-level provisioning script for it (unlike the Postgres databases,
     * which init-multiple-dbs.sh creates) — so the service takes responsibility for its own bucket.
     */
    @Bean
    public ApplicationRunner ensureBucketExists(MinioClient minioClient, MinioProperties props) {
        return (ApplicationArguments args) -> {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(props.getBucket()).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
                log.info("Created MinIO bucket '{}'", props.getBucket());
            }
        };
    }
}
