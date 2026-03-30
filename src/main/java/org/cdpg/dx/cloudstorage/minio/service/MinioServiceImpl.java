package org.cdpg.dx.cloudstorage.minio.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.http.Method;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MinioServiceImpl implements MinioService {
  private static final Logger LOGGER = LogManager.getLogger(MinioServiceImpl.class);
  private static final int STAT_RETRIES = 6;
  private static final long STAT_RETRY_DELAY_MS = 1000L;
  private final Vertx vertx;
  private final MinioClient client;
  private final String bucket;
  private final int presignedExpirySeconds;

  public MinioServiceImpl(Vertx vertx, MinioClient client, String bucket, int presignedExpiry) {
    this.vertx = vertx;
    this.client = client;
    this.bucket = bucket;
    this.presignedExpirySeconds = presignedExpiry;
  }

  private Future<Void> ensureBucket() {
    return vertx.executeBlocking(
        promise -> {
          try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
              client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            promise.complete();
          } catch (Exception e) {
            promise.fail(e);
          }
        });
  }

  @Override
  public Future<String> uploadObject(String objectName, String base64Data, String contentType) {
    return ensureBucket()
        .compose(
            v ->
                vertx.executeBlocking(
                    promise -> {
                      try {
                        byte[] data = Base64.getDecoder().decode(base64Data);
                        LOGGER.info(
                            "MinIO put start bucket={} object={} sizeBytes={}",
                            bucket,
                            objectName,
                            data.length);
                        ObjectWriteResponse writeResponse =
                            client.putObject(
                                PutObjectArgs.builder().bucket(bucket).object(objectName).stream(
                                        new ByteArrayInputStream(data), data.length, -1)
                                    .contentType(contentType)
                                    .build());
                        LOGGER.info("MinIO put done bucket={} object={}", bucket, objectName);
                        if (writeResponse != null) {
                          LOGGER.info(
                              "MinIO write response bucket={} object={} etag={} versionId={}",
                              bucket,
                              objectName,
                              writeResponse.etag(),
                              writeResponse.versionId());
                        } else {
                          LOGGER.warn(
                              "MinIO write response is null bucket={} object={} (client returned null)",
                              bucket,
                              objectName);
                        }
                        verifyObjectReadable(objectName);

                        String url =
                            client.getPresignedObjectUrl(
                                GetPresignedObjectUrlArgs.builder()
                                    .bucket(bucket)
                                    .object(objectName)
                                    .method(Method.GET)
                                    .expiry(presignedExpirySeconds)
                                    .build());
                        if (url == null || url.isBlank()) {
                          throw new IllegalStateException(
                              "MinIO generated empty presigned URL for object " + objectName);
                        }
                        LOGGER.info(
                            "MinIO presigned URL generated bucket={} object={} urlLength={}",
                            bucket,
                            objectName,
                            url.length());
                        promise.complete(url);
                      } catch (Exception e) {
                        LOGGER.error(
                            "MinIO upload failed bucket={} object={} error={}",
                            bucket,
                            objectName,
                            e.toString());
                        promise.fail(e);
                      }
                    }));
  }

  @Override
  public Future<String> uploadObjectFromFile(
      String objectName, String filePath, String contentType) {
    return ensureBucket()
        .compose(
            v ->
                vertx.executeBlocking(
                    promise -> {
                      Path path = Path.of(filePath);
                      try {
                        long size = Files.size(path);
                        LOGGER.info(
                            "MinIO put(file) start bucket={} object={} sizeBytes={}",
                            bucket,
                            objectName,
                            size);
                        ObjectWriteResponse writeResponse =
                            client.uploadObject(
                                UploadObjectArgs.builder()
                                    .bucket(bucket)
                                    .object(objectName)
                                    .filename(filePath)
                                    .contentType(contentType)
                                    .build());
                        LOGGER.info("MinIO put(file) done bucket={} object={}", bucket, objectName);
                        if (writeResponse != null) {
                          LOGGER.info(
                              "MinIO write response bucket={} object={} etag={} versionId={}",
                              bucket,
                              objectName,
                              writeResponse.etag(),
                              writeResponse.versionId());
                        } else {
                          LOGGER.warn(
                              "MinIO write response is null bucket={} object={} (client returned null)",
                              bucket,
                              objectName);
                        }
                        verifyObjectReadable(objectName);
                        String url =
                            client.getPresignedObjectUrl(
                                GetPresignedObjectUrlArgs.builder()
                                    .bucket(bucket)
                                    .object(objectName)
                                    .method(Method.GET)
                                    .expiry(presignedExpirySeconds)
                                    .build());
                        if (url == null || url.isBlank()) {
                          throw new IllegalStateException(
                              "MinIO generated empty presigned URL for object " + objectName);
                        }
                        LOGGER.info(
                            "MinIO presigned URL generated bucket={} object={} urlLength={}",
                            bucket,
                            objectName,
                            url.length());
                        promise.complete(url);
                      } catch (Exception e) {
                        LOGGER.error(
                            "MinIO upload(file) failed bucket={} object={} path={} error={}",
                            bucket,
                            objectName,
                            filePath,
                            e.toString());
                        promise.fail(e);
                      }
                    }));
  }

  @Override
  public Future<String> getBucketName() {
    return Future.succeededFuture(bucket);
  }

  private void verifyObjectReadable(String objectName) throws Exception {
    Exception last = null;
    for (int i = 1; i <= STAT_RETRIES; i++) {
      try (InputStream in =
          client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectName).build())) {
        int firstByte = in.read();
        LOGGER.info(
            "MinIO read-back success bucket={} object={} firstByte={} attempt={}",
            bucket,
            objectName,
            firstByte,
            i);
        return;
      } catch (Exception e) {
        last = e;
        LOGGER.warn(
            "MinIO read-back failed bucket={} object={} attempt={} error={}",
            bucket,
            objectName,
            i,
            e.getMessage());
        Thread.sleep(STAT_RETRY_DELAY_MS);
      }
    }
    throw last;
  }
}
