package org.cdpg.dx.cloudstorage.s3.service;

import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;

@VertxGen
@ProxyGen
public interface S3FileService {
  @GenIgnore
  static S3FileService createProxy(Vertx vertx, String address) {
    return new S3FileServiceVertxEBProxy(vertx, address);
  }

  @GenIgnore
  static S3FileService createProxy(Vertx vertx, String address, long sendTimeoutMs) {
    DeliveryOptions options = new DeliveryOptions().setSendTimeout(sendTimeoutMs);
    return new S3FileServiceVertxEBProxy(vertx, address, options);
  }

  Future<JsonObject> uploadObject(
      String objectName, String base64Data, String contentType, String fileName);

  Future<JsonObject> uploadObjectFromFile(String objectName, String filePath);
}
