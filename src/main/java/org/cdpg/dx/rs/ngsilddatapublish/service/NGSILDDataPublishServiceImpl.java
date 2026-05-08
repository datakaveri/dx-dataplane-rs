package org.cdpg.dx.rs.ngsilddatapublish.service;

import static org.cdpg.dx.databroker.util.Constants.ID;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.cloudstorage.minio.service.MinioService;
import org.cdpg.dx.cloudstorage.s3.service.S3FileService;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.databroker.model.ExchangeSubscribersResponse;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.databroker.util.Vhosts;
import org.cdpg.dx.rs.indexgenerator.IndexNameCreation;

public class NGSILDDataPublishServiceImpl implements NGSILDDataPublishService {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDDataPublishServiceImpl.class);

  private final DataBrokerService dataBrokerService;
  private final ElasticsearchService elasticsearchService;
  private final MinioService minioService;
  private final S3FileService s3FileService;

  public NGSILDDataPublishServiceImpl(
      DataBrokerService dataBrokerService,
      ElasticsearchService elasticsearchService,
      MinioService minioService,
      S3FileService s3FileService) {
    this.dataBrokerService = dataBrokerService;
    this.elasticsearchService = elasticsearchService;
    this.minioService = minioService;
    this.s3FileService = s3FileService;
  }

  @Override
  public Future<String> publishData(JsonArray ngsildData, String id) {

    for (int i = 0; i < ngsildData.size(); i++) {
      JsonObject jsonObject = ngsildData.getJsonObject(i);
      jsonObject.remove("entities");
      jsonObject.put(ID, id);
    }
    LOGGER.trace("Final request payload: {}", ngsildData.encodePrettily());

    return dataBrokerService
        .publishMessageExternal(id, id, ngsildData)
        .onSuccess(
            v -> {
              LOGGER.info("Data published successfully for id: {}", id);
            })
        .onFailure(
            err -> {
              LOGGER.error("Failed to publish data for id: {}. Error: {}", id, err.getMessage());
            });
  }

  @Override
  public Future<String> uploadBatchToMinio(JsonArray pushedData, String id) {
    String objectName = buildObjectName(id, ".json");
    return minioService
        .uploadObject(objectName, encodeToBase64(pushedData.toBuffer()), "application/json")
        .compose(
            presignedUrl ->
                minioService
                    .getBucketName()
                    .recover(err -> Future.succeededFuture("unknown"))
                    .map(
                        bucketName -> {
                          LOGGER.info(
                              "Uploaded on-seek batch for id {} to MinIO bucket {} at {}. Presigned URL: {}",
                              id,
                              bucketName,
                              objectName,
                              presignedUrl);
                          return presignedUrl;
                        }));
  }

  @Override
  public Future<String> uploadBatchToMinioAndPublishMetadata(JsonArray pushedData, String id) {
    for (int i = 0; i < pushedData.size(); i++) {
      JsonObject jsonObject = pushedData.getJsonObject(i);
      jsonObject.remove("entities");
      jsonObject.put(ID, id);
    }

    String objectName = buildObjectName(id, ".json");

    Promise<String> promise = Promise.promise();
    try {
      Path tempFile = writeTempJsonFile(pushedData);
      Buffer buffer = Buffer.buffer(Files.readAllBytes(tempFile));

      minioService
          .uploadObject(objectName, encodeToBase64(buffer), "application/json")
          .compose(
              presignedUrl -> {
                JsonObject metadata =
                    new JsonObject()
                        .put("fileId", objectName)
                        .put("presignedUrl", presignedUrl)
                        .put(ID, id);
                JsonArray msg = new JsonArray().add(metadata);
                return dataBrokerService
                    .publishMessageExternal(id, id, msg)
                    .compose(
                        v ->
                            minioService
                                .getBucketName()
                                .recover(err -> Future.succeededFuture("unknown"))
                                .map(
                                    bucketName -> {
                                      LOGGER.info(
                                          "Uploaded on-seek batch for id {} to bucket {} at {} and published metadata. Presigned URL: {}",
                                          id,
                                          bucketName,
                                          objectName,
                                          presignedUrl);
                                      return presignedUrl;
                                    }));
              })
          .onComplete(
              ar -> {
                try {
                  Files.deleteIfExists(tempFile);
                } catch (Exception ex) {
                  LOGGER.warn("Failed to delete temp file {}: {}", tempFile, ex.getMessage());
                }
                if (ar.succeeded()) {
                  promise.complete(ar.result());
                } else {
                  promise.fail(ar.cause());
                }
              });
    } catch (Exception e) {
      promise.fail(e);
    }

    return promise.future();
  }

  @Override
  public Future<String> uploadFileToCloudAndPublishMetadata(
      Buffer data, String id, String contentType) {
    String objectName = buildObjectName(id, ".bin");

    String resolvedContentType =
        (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;

    return s3FileService
        .uploadObject(objectName, encodeToBase64(data), resolvedContentType, objectName)
        .compose(
            result -> {
              String presignedUrl = result.getString("s3_url");
              return publishFileMetadata(id, objectName, presignedUrl);
            });
  }

  @Override
  public Future<String> uploadFileToCloudAndPublishMetadata(
      Path filePath, String id, String contentType) {
    String objectName = buildObjectName(id, ".bin");

    String resolvedContentType =
        (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;

    return s3FileService
        .uploadObjectFromFile(objectName, filePath.toString())
        .compose(
            result -> {
              String presignedUrl = result.getString("s3_url");
              return publishFileMetadata(id, objectName, presignedUrl);
            });
  }

  @Override
  public Future<String> publishDataOnSeek(JsonArray pushedData, String id) {
    for (int i = 0; i < pushedData.size(); i++) {
      JsonObject jsonObject = pushedData.getJsonObject(i);
      jsonObject.remove("entities");
      jsonObject.put(ID, id);
    }
    // LOGGER.trace("Final payload: {}", pushedData.encodePrettily());

    return dataBrokerService
        .publishMessageExternal(id, id, pushedData)
        .onSuccess(
            v -> {
              LOGGER.info("Data published successfully using on seek for id: {}", id);
            })
        .onFailure(
            err -> {
              LOGGER.error("Failed to published data for id: {}. Error: {}", id, err.getMessage());
            });
  }

  @Override
  public Future<String> publishDataOnSeekIntoElastic(JsonArray pushedData, String id) {
    String index = IndexNameCreation.createIndex(id);
    List<QueryModel> docs = new ArrayList<>();
    for (int i = 0; i < pushedData.size(); i++) {
      JsonObject jsonObject = pushedData.getJsonObject(i);
      jsonObject.remove("entities");
      jsonObject.put(ID, id);
      QueryModel model = new QueryModel();
      model.createQueryModelFromDocument(jsonObject);
      docs.add(model);
    }
    return elasticsearchService
        .createDocumentsAutoId(index, docs)
        .compose(
            ids -> {
              LOGGER.info(
                  "Data indexed successfully using on seek for id: {}. Count: {}", id, ids.size());
              return dataBrokerService
                  .listExchange(id, Vhosts.IUDX_PROD)
                  .compose(
                      exchangeSubs ->
                          publishToAllQueuesExceptDatabase(exchangeSubs, id, pushedData));
            })
        .onFailure(
            err -> {
              LOGGER.error(
                  "Failed to index/publish data using on seek for id: {}. Error: {}",
                  id,
                  err.getMessage());
            });
  }

  private Future<String> publishToAllQueuesExceptDatabase(
      ExchangeSubscribersResponse exchangeSubs, String id, JsonArray pushedData) {
    if (exchangeSubs == null || exchangeSubs.getSubscribers() == null) {
      LOGGER.info("RMQ publish skipped for id {}: no subscribers", id);
      return Future.succeededFuture("no-subscribers");
    }
    List<Future<?>> publishes = new ArrayList<>();

    exchangeSubs
        .getSubscribers()
        .forEach(
            (queue, routingKeys) -> {
              if ("database".equalsIgnoreCase(queue)) {
                return;
              }
              if (routingKeys == null || routingKeys.isEmpty()) {
                return;
              }
              for (String rk : routingKeys) {
                publishes.add(dataBrokerService.publishMessageExternal(id, rk, pushedData));
              }
            });
    if (publishes.isEmpty()) {
      LOGGER.info("RMQ publish skipped for id {}: no target queues", id);
      return Future.succeededFuture("no-target-queues");
    }
    return Future.join(publishes)
        .map(
            ignored -> {
              LOGGER.info("RMQ published for id {}", id);
              return "published";
            });
  }

  @Override
  public Future<String> uploadPathToMinioAndPublishMetadata(
      Path path, String id, String contentType, String originalName) {
    String objectName = buildObjectName(id, resolveExtension(originalName));

    return s3FileService
        .uploadObjectFromFile(objectName, path.toString())
        .compose(
            result -> {
              String presignedUrl = result.getString("s3_url");
              return publishFileMetadata(id, objectName, presignedUrl);
            });
  }

  private String resolveExtension(String originalName) {
    if (originalName == null || originalName.isBlank()) {
      return ".bin";
    }
    int dot = originalName.lastIndexOf('.');
    if (dot <= 0 || dot == originalName.length() - 1) {
      return ".bin";
    }
    return originalName.substring(dot);
  }

  private String encodeToBase64(Buffer buffer) {
    return Base64.getEncoder().encodeToString(buffer.getBytes());
  }

  private Future<String> publishFileMetadata(String id, String objectName, String presignedUrl) {
    if (presignedUrl == null || presignedUrl.isBlank()) {
      return Future.failedFuture(
          "Presigned URL is empty for object " + objectName + " in on-seek flow");
    }
    JsonObject metadata =
        new JsonObject().put("fileId", objectName).put("presignedUrl", presignedUrl).put(ID, id);
    JsonArray msg = new JsonArray().add(metadata);
    return dataBrokerService
        .publishMessageExternal(id, id, msg)
        .map(
            v -> {
              LOGGER.info(
                  "Uploaded on-seek file for id {} to {} and published metadata. Presigned URL: {}",
                  id,
                  objectName,
                  presignedUrl);
              return presignedUrl;
            });
  }

  private String buildObjectName(String id, String extension) {
    String prefix = (id == null || id.isBlank()) ? "unknown" : id;
    return prefix + "-" + UUID.randomUUID() + extension;
  }

  private Path writeTempJsonFile(JsonArray data) throws Exception {
    Path tempFile = Files.createTempFile("onseek-", ".json");
    Files.writeString(
        tempFile, data.encode(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    return tempFile;
  }
}
