package org.cdpg.dx.cloudstorage.util;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class S3FileOpsHelper {

  private static final Logger LOGGER = LogManager.getLogger(S3FileOpsHelper.class);

  private final String endpoint;
  private final String region;
  private final String accessKey;
  private final String secretKey;
  private final String bucketName;

  // KEEP YOUR ORIGINAL CONSTRUCTOR (values come from config)
  public S3FileOpsHelper(
      String endpoint, String region, String accessKey, String secretKey, String bucketName) {
    this.endpoint = endpoint;
    this.region = region;
    this.accessKey = accessKey;
    this.secretKey = secretKey;
    this.bucketName = bucketName;
  }

  private S3Client buildS3Client() {
    S3ClientBuilder builder =
        S3Client.builder()
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .region(Region.of(region));

    if (accessKey != null && !accessKey.isEmpty() && secretKey != null && !secretKey.isEmpty()) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
    } else {
      builder.credentialsProvider(DefaultCredentialsProvider.create());
    }

    if (endpoint != null && !endpoint.isEmpty()) {
      builder.endpointOverride(URI.create(endpoint));
    }

    // Set timeouts
    builder.overrideConfiguration(
        ClientOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(240))
            .apiCallAttemptTimeout(Duration.ofSeconds(120))
            .build());

    return builder.build();
  }

  private S3Presigner buildPresigner() {
    S3Presigner.Builder builder = S3Presigner.builder().region(Region.of(region));

    if (accessKey != null && !accessKey.isEmpty() && secretKey != null && !secretKey.isEmpty()) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
    } else {
      builder.credentialsProvider(DefaultCredentialsProvider.create());
    }

    if (endpoint != null && !endpoint.isEmpty()) {
      builder.endpointOverride(URI.create(endpoint));
    }

    // enable path style (helps MinIO)
    builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());

    return builder.build();
  }

  public Future<JsonObject> s3Upload(File file, String objectKey) {
    Promise<JsonObject> promise = Promise.promise();

    try (S3Client s3Client = buildS3Client()) {

      PutObjectRequest putRequest =
          PutObjectRequest.builder()
              .bucket(bucketName)
              .key(objectKey)
              .contentDisposition("attachment; filename=" + file.getName())
              .build();

      s3Client.putObject(putRequest, RequestBody.fromFile(file));
      LOGGER.info("Object upload complete");

      JsonObject result = createUploadResult(objectKey);

      promise.complete(result);

    } catch (S3Exception e) {
      LOGGER.error("S3 upload error", e);
      promise.fail(e);
    } catch (Exception e) {
      LOGGER.error("Unexpected error during S3 upload", e);
      promise.fail(e);
    }

    return promise.future();
  }

  public Future<JsonObject> s3Upload(
      byte[] bytes, String objectKey, String contentType, String fileName) {
    Promise<JsonObject> promise = Promise.promise();

    try (S3Client s3Client = buildS3Client()) {
      PutObjectRequest.Builder builder =
          PutObjectRequest.builder()
              .bucket(bucketName)
              .key(objectKey)
              .contentDisposition(
                  "attachment; filename=" + (fileName == null ? objectKey : fileName));

      if (contentType != null && !contentType.isBlank()) {
        builder.contentType(contentType);
      }

      s3Client.putObject(builder.build(), RequestBody.fromBytes(bytes));
      LOGGER.info("Object upload complete");

      JsonObject result = createUploadResult(objectKey);
      promise.complete(result);
    } catch (S3Exception e) {
      LOGGER.error("S3 upload error", e);
      promise.fail(e);
    } catch (Exception e) {
      LOGGER.error("Unexpected error during S3 upload", e);
      promise.fail(e);
    }

    return promise.future();
  }

  public URL generatePreSignedUrl(long expiryValue, String objectKey) {
    long now = System.currentTimeMillis();
    long durationMillis = expiryValue - now; // convert absolute expiry time to duration

    // Clamp to AWS allowed range [1 second .. 7 days]
    long minMillis = 1000L;
    long maxMillis = Duration.ofDays(7).toMillis();
    if (durationMillis < minMillis) durationMillis = minMillis;
    else if (durationMillis > maxMillis) durationMillis = maxMillis;

    try (S3Presigner presigner = buildPresigner()) {
      GetObjectRequest getRequest =
          GetObjectRequest.builder().bucket(bucketName).key(objectKey).build();

      GetObjectPresignRequest presignRequest =
          GetObjectPresignRequest.builder()
              .getObjectRequest(getRequest)
              .signatureDuration(Duration.ofMillis(durationMillis))
              .build();

      return presigner.presignGetObject(presignRequest).url();

    } catch (Exception e) {
      LOGGER.error("Presigned URL generation failed", e);
      return null;
    }
  }

  private JsonObject createUploadResult(String objectKey) {
    ZonedDateTime zdt = ZonedDateTime.now().plusDays(7);
    long expiryEpochMillis = zdt.toEpochSecond() * 1000L;
    URL presigned = generatePreSignedUrl(expiryEpochMillis, objectKey);
    return new JsonObject()
        .put("s3_url", presigned == null ? null : presigned.toString())
        .put("expiry", zdt.toLocalDateTime().toString())
        .put("object_id", objectKey);
  }
}
