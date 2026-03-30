package org.cdpg.dx.cloudstorage.s3.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import java.io.File;
import java.util.Base64;
import org.cdpg.dx.cloudstorage.util.S3FileOpsHelper;

public class S3FileServiceImpl implements S3FileService {
  private final S3FileOpsHelper s3FileOpsHelper;

  public S3FileServiceImpl(S3FileOpsHelper s3FileOpsHelper) {
    this.s3FileOpsHelper = s3FileOpsHelper;
  }

  @Override
  public Future<JsonObject> uploadObject(
      String objectName, String base64Data, String contentType, String fileName) {
    Promise<JsonObject> promise = Promise.promise();
    if (base64Data == null) {
      promise.fail(new IllegalArgumentException("base64Data cannot be null"));
      return promise.future();
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(base64Data);
      s3FileOpsHelper
          .s3Upload(bytes, objectName, contentType, fileName)
          .onSuccess(promise::complete)
          .onFailure(promise::fail);
    } catch (IllegalArgumentException e) {
      promise.fail(e);
    }
    return promise.future();
  }

  @Override
  public Future<JsonObject> uploadObjectFromFile(String objectName, String filePath) {
    return s3FileOpsHelper.s3Upload(new File(filePath), objectName);
  }
}
