package com.github.wellch4n.oops.infrastructure.config;

import java.net.URI;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@ConditionalOnProperty(prefix = "oops.object-storage", name = "enabled", havingValue = "true")
public class S3PresignerConfiguration {

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(ObjectStorageProperties config) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())
                ))
                .region(Region.of(config.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.isPathStyleAccess())
                        .build());
        if (StringUtils.isNotBlank(config.getEndpoint())) {
            builder.endpointOverride(URI.create(config.getEndpoint()));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public S3Client s3Client(ObjectStorageProperties config) {
        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())
                ))
                .region(Region.of(config.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.isPathStyleAccess())
                        .build());
        if (StringUtils.isNotBlank(config.getEndpoint())) {
            builder.endpointOverride(URI.create(config.getEndpoint()));
        }
        return builder.build();
    }
}
