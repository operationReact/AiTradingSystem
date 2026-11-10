package in.aitradingsystem.config;

import in.aitradingsystem.marketdata.GoldenS3ArchiveAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Research-only Golden Source storage, OFF by default. Opting in requires a
 * separately approved private, versioned, KMS-encrypted bucket and IAM role.
 * Does not connect the historical collector or enable any scheduled work.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "minttick.golden-source.s3",
        name = "enabled", havingValue = "true")
public class GoldenS3ArchiveConfig {
    @Bean(destroyMethod = "close")
    S3Client goldenSourceS3Client() {
        return S3Client.builder()
                .region(Region.AP_SOUTH_1)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    GoldenS3ArchiveAdapter goldenS3ArchiveAdapter(
            S3Client goldenSourceS3Client,
            @Value("${minttick.golden-source.s3.bucket:}") String bucket,
            @Value("${minttick.golden-source.s3.expected-owner:}") String expectedOwner,
            @Value("${minttick.golden-source.s3.kms-key-arn:}") String kmsKeyArn,
            @Value("${minttick.golden-source.s3.prefix:golden-source/bronze/v1/}") String prefix) {
        return new GoldenS3ArchiveAdapter(
                goldenSourceS3Client, bucket, expectedOwner, kmsKeyArn, prefix);
    }
}
