package org.cdpg.dx.cloudstorage.minio.service;

import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;

@VertxGen
@ProxyGen
public interface MinioService {
  @GenIgnore
  static MinioService createProxy(Vertx vertx, String address) {
    return new MinioServiceVertxEBProxy(vertx, address);
  }

  @GenIgnore
  static MinioService createProxy(Vertx vertx, String address, long sendTimeoutMs) {
    DeliveryOptions options = new DeliveryOptions().setSendTimeout(sendTimeoutMs);
    return new MinioServiceVertxEBProxy(vertx, address, options);
  }

  /**
   * Upload an object to MinIO and return a presigned GET URL.
   *
   * @param objectName object key to store under
   * @param base64Data base64-encoded file content
   * @param contentType mime type
   * @return presigned URL valid for configured expiry duration
   */
  Future<String> uploadObject(String objectName, String base64Data, String contentType);

  /**
   * Upload an object to MinIO from a local file path and return a presigned GET URL.
   *
   * @param objectName object key to store under
   * @param filePath local file path
   * @param contentType mime type
   * @return presigned URL valid for configured expiry duration
   */
  Future<String> uploadObjectFromFile(String objectName, String filePath, String contentType);

  /**
   * Return the configured bucket name.
   *
   * @return bucket name
   */
  Future<String> getBucketName();
}
