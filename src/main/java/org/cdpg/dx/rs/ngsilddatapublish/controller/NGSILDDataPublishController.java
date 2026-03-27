package org.cdpg.dx.rs.ngsilddatapublish.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.HEADER_ALLOW_ORIGIN;
import static org.cdpg.dx.databroker.util.Constants.*;
import static org.cdpg.dx.rs.audit.util.Constants.*;
import static org.cdpg.dx.rs.ngsilddatapublish.util.Constants.*;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auditing.model.AuditLog;
import org.cdpg.dx.auth.authorization.handler.AuthorizationHandler;
import org.cdpg.dx.auth.authorization.model.DxRole;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.rs.audit.util.DataplaneAuditHelper;
import org.cdpg.dx.rs.ngsilddatapublish.service.NGSILDDataPublishService;
import org.cdpg.dx.rs.ngsilddatapublish.util.S3FileOpsHelper;
import org.cdpg.dx.validations.idhandler.GetIdForIngestionEntityHandler;
import org.cdpg.dx.validations.idhandler.GetIdFromPathHandler;
import org.cdpg.dx.validations.idvalidation.IdValidation;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessDataPublishHandler;
import org.cdpg.dx.validations.provider.ProviderDelegateValidationHandler;

public class NGSILDDataPublishController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDDataPublishController.class);
  private static final boolean KEEP_ONSEEK_TEMP_FILES =
      Boolean.parseBoolean(System.getenv().getOrDefault("KEEP_ONSEEK_TEMP_FILES", "true"));
  private final int chunkMaxItems;
  private final int chunkMaxBytes;
  private final IdValidation idValidation;
  private final GetIdForIngestionEntityHandler getIdForIngestionEntityHandler;
  private final GetIdFromPathHandler getIdFromPathHandler;
  private final ItemAccessDataPublishHandler itemAccessDataPublishHandler;
  private final ProviderDelegateValidationHandler providerDelegateValidationHandler;
  private NGSILDDataPublishService ngsildDataPublishService;
  private URNGenerator urnGenerator;
  private AuditingHandler auditingHandler;
  private S3FileOpsHelper s3FileOpsHelper;

  public NGSILDDataPublishController(
          NGSILDDataPublishService ngsildDataPublishService,
          String controlPlaneDomain,
          URNGenerator urnGenerator,
          AuditingHandler auditingHandler,
          int chunkMaxItems,
          int chunkMaxBytes, S3FileOpsHelper fileOpsHelper) {
      this.s3FileOpsHelper = fileOpsHelper;
    this.auditingHandler = auditingHandler;
    this.chunkMaxItems = chunkMaxItems;
    this.chunkMaxBytes = chunkMaxBytes * 1024 * 1024; // Convert MB to Bytes
    this.itemAccessDataPublishHandler = new ItemAccessDataPublishHandler(controlPlaneDomain);
    this.urnGenerator = urnGenerator;
    this.ngsildDataPublishService = ngsildDataPublishService;
    this.idValidation = new IdValidation();
    this.getIdForIngestionEntityHandler = new GetIdForIngestionEntityHandler();
    this.getIdFromPathHandler = new GetIdFromPathHandler();
    this.providerDelegateValidationHandler = new ProviderDelegateValidationHandler();
  }

  @Override
  public void register(RouterBuilder builder) {
    builder
        .operation(POST_NGSILD_ENTITY_PUBLISH)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdForIngestionEntityHandler)
        .handler(AuthorizationHandler.forRoles(DxRole.PROVIDER, DxRole.DELEGATE))
        .handler(itemAccessDataPublishHandler)
        .handler(providerDelegateValidationHandler)
        .handler(idValidation)
        .handler(context -> handleDataPublish(context));
    builder
        .operation(POST_NGSILD_ENTITY_PUBLISH_ONSEEK)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(AuthorizationHandler.forRoles(DxRole.PROVIDER, DxRole.DELEGATE))
        .handler(itemAccessDataPublishHandler)
        .handler(providerDelegateValidationHandler)
        .handler(idValidation)
        .handler(this::handleDataPublishOnSeek);

    builder
        .operation(POST_NGSILD_ENTITY_PUBLISH_ALIAS)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(AuthorizationHandler.forRoles(DxRole.PROVIDER, DxRole.DELEGATE))
        .handler(itemAccessDataPublishHandler)
        .handler(providerDelegateValidationHandler)
        .handler(idValidation)
        .handler(this::handleDataPublishAlias);
  }

  public void handleDataPublish(RoutingContext context) {
    LOGGER.info("Handling NGSILD Data Publish Request");
    String id = RoutingContextHelper.getId(context);
    streamAndPublish(context, id, false);
  }

  public void handleDataPublishAlias(RoutingContext context) {
    LOGGER.info("Handling NGSI-LD Data Publish Request Alias");
    String id = RoutingContextHelper.getId(context);
    if (id == null) {
      id = context.pathParam("id");
    }
    streamAndPublish(context, id, true);
  }

  public void handleDataPublishOnSeek(RoutingContext context) {
    LOGGER.info("Handling NGSI-LD Data Publish on seek");
    String id = RoutingContextHelper.getId(context);
    if (id == null) {
      id = context.pathParam("id");
    }
    streamRequestToLocalFileAndUpload(context, id);
  }

  private void streamRequestToLocalFileAndUpload(RoutingContext context, String id) {
    Path tempFile;
    String contentType = context.request().getHeader("Content-Type");
    String resolvedContentType =
        (contentType == null || contentType.isBlank()) ? "application/octet-stream" : contentType;
    try {
      tempFile = Files.createTempFile("onseek-", resolveExtensionFromContentType(contentType));
      LOGGER.info("On-seek temp file created for id {} at {}", id, tempFile.toAbsolutePath());
    } catch (Exception e) {
      context.fail(e);
      return;
    }

    Buffer bufferedBody = context.body() != null ? context.body().buffer() : null;
    if (bufferedBody != null && bufferedBody.length() > 0) {
      context
          .vertx()
          .fileSystem()
          .writeFile(tempFile.toString(), bufferedBody)
          .compose(v -> uploadOnSeekTempFile(context, id, tempFile, resolvedContentType))
          .onSuccess(v -> respondSuccess(context, context.response(), id))
          .onFailure(
              err -> {
                deleteTempFile(tempFile);
                context.fail(err);
              });
      return;
    }

    if (context.request().isEnded()) {
      deleteTempFile(tempFile);
      context.fail(new DxBadRequestException("Empty request body"));
      return;
    }

    context
        .vertx()
        .fileSystem()
        .open(
            tempFile.toString(),
            new OpenOptions().setCreate(true).setWrite(true).setTruncateExisting(true))
        .onSuccess(
            asyncFile -> {
              context.request().pause();
              context.request().handler(asyncFile::write);
              context.request()
                  .endHandler(
                      ignored ->
                          asyncFile
                              .close()
                              .compose(v -> uploadOnSeekTempFile(context, id, tempFile, resolvedContentType))
                              .onSuccess(v -> respondSuccess(context, context.response(), id))
                              .onFailure(
                                  err -> {
                                    deleteTempFile(tempFile);
                                    context.fail(err);
                                  }));
              context.request()
                  .exceptionHandler(
                      err ->
                          asyncFile
                              .close()
                              .onComplete(ignored -> deleteTempFile(tempFile))
                              .onComplete(ignored -> context.fail(err)));
              context.request().resume();
            })
        .onFailure(
            err -> {
              deleteTempFile(tempFile);
              context.fail(err);
            });
  }

  private Future<Void> uploadOnSeekTempFile(
      RoutingContext context, String id, Path tempFile, String contentType) {
    return context
        .vertx()
        .executeBlocking(
            promise -> {
              try {
                long size = Files.size(tempFile);
                LOGGER.info(
                    "On-seek temp file ready for upload id {} path {} sizeBytes={}",
                    id,
                    tempFile.toAbsolutePath(),
                    size);
                promise.complete();
              } catch (Exception e) {
                promise.fail(e);
              }
            })
        .compose(
            ignored ->
                ngsildDataPublishService.uploadPathToMinioAndPublishMetadata(
                    tempFile, id, contentType, tempFile.getFileName().toString()))
        .map(
            ignored -> {
              LOGGER.info("On-seek raw upload complete for id {}", id);
              deleteTempFile(tempFile);
              return null;
            });
  }

  private void deleteTempFile(Path tempFile) {
    if (KEEP_ONSEEK_TEMP_FILES) {
      LOGGER.warn("Keeping on-seek temp file for debugging at {}", tempFile.toAbsolutePath());
      return;
    }
    try {
      Files.deleteIfExists(tempFile);
    } catch (Exception e) {
      LOGGER.warn("Failed to delete temp file {}: {}", tempFile, e.getMessage());
    }
  }

  private String resolveExtensionFromContentType(String contentType) {
    if (contentType == null) {
      return ".bin";
    }
    String lower = contentType.toLowerCase();
    if (lower.contains("json")) {
      return ".json";
    }
    if (lower.contains("csv")) {
      return ".csv";
    }
    if (lower.contains("xml")) {
      return ".xml";
    }
    return ".bin";
  }

  private void streamAndPublish(RoutingContext context, String id, boolean onSeek) {
    streamWithVertxParser(context, id, onSeek);
  }

  private void streamWithVertxParser(RoutingContext context, String id, boolean onSeek) {
    io.vertx.core.parsetools.JsonParser parser =
        io.vertx.core.parsetools.JsonParser.newParser(context.request());
    parser.objectValueMode();
    parser.pause();

    JsonArray batch = new JsonArray();
    int[] batchBytes = {0};
    long[] totalCount = {0};
    boolean[] failed = {false};
    String[] idRef = {id};

    parser.handler(
        event -> {
          if (failed[0]) {
            return;
          }
          parser.pause();
          JsonObject obj = event.objectValue();
          if (obj == null) {
            // Skip non-object tokens (e.g., start/end array) in stream.
            parser.resume();
            return;
          }
          if (idRef[0] == null) {
            idRef[0] = obj.getString("entities");
          }
          if (idRef[0] == null || idRef[0].isBlank()) {
            failed[0] = true;
            context.fail(new DxBadRequestException("Missing id in payload"));
            return;
          }
          batch.add(obj);
          batchBytes[0] += obj.encode().length();
          totalCount[0]++;

          if (batch.size() >= chunkMaxItems || batchBytes[0] >= chunkMaxBytes) {
            flushBatch(context, batch.copy(), idRef[0], batchBytes, batch, failed, onSeek)
                .onSuccess(v -> parser.resume())
                .onFailure(err -> failed[0] = true);
          } else {
            parser.resume();
          }
        });

    parser.exceptionHandler(
        err -> {
          failed[0] = true;
          context.fail(new DxBadRequestException("Invalid JSON: " + err.getMessage()));
        });

    parser.endHandler(
        v -> {
          if (failed[0]) {
            return;
          }
          if (batch.isEmpty()) {
            LOGGER.info("Total records received for id {}: {}", idRef[0], totalCount[0]);
            LOGGER.info(
                "Publish completes for id {} Total records received: {}", idRef[0], totalCount[0]);
            respondSuccess(context, context.response(), idRef[0]);
          } else {
            flushBatch(context, batch.copy(), idRef[0], batchBytes, batch, failed, onSeek)
                .onSuccess(
                    ignored -> {
                      LOGGER.info("Total record's received for id {}: {}", idRef[0], totalCount[0]);
                      LOGGER.info(
                          "Publish complete for id {}. Total records received: {}",
                          idRef[0],
                          totalCount[0]);
                      respondSuccess(context, context.response(), idRef[0]);
                    })
                .onFailure(context::fail);
          }
        });

    parser.resume();
  }

  private Future<String> flushBatch(
      RoutingContext context,
      JsonArray currentBatch,
      String id,
      int[] batchBytes,
      JsonArray batch,
      boolean[] failed,
      boolean onSeek) {
    batch.clear();
    batchBytes[0] = 0;
    Future<String> publishFuture =
        onSeek
            ? ngsildDataPublishService.publishDataOnSeekIntoElastic(currentBatch, id)
            : ngsildDataPublishService.publishData(currentBatch, id);
    return publishFuture.onFailure(
        err -> {
          failed[0] = true;
          context.fail(err);
        });
  }

  private void respondSuccess(RoutingContext context, HttpServerResponse response, String id) {
    JsonObject finalResponse = new JsonObject();
    finalResponse.put(DETAIL, "Item Published");
    JsonArray userRoles =
        context.user().principal().getJsonObject("realm_access").getJsonArray("roles");
    String role = userRoles.contains("provider") ? "provider" : "delegate";
    String delegatorId;
    if (role.equalsIgnoreCase("delegate")) {
      delegatorId = context.request().getHeader("did");
    } else {
      delegatorId = context.user().subject();
    }
    AuditLog auditLog =
        DataplaneAuditHelper.createAuditingLogs(
            RoutingContextHelper.getItemMetaData(context),
            id,
            RoutingContextHelper.getRequestPath(context),
            "POST",
            context.user().subject(),
            NGSILD,
            role,
            CREATE,
            context.user().principal().getString("iss"),
            delegatorId,
            0L);
    RoutingContextHelper.setAuditingLog(context, auditLog);
    response
        .putHeader("Content-Type", "application/json")
        .putHeader(HEADER_ALLOW_ORIGIN, "*")
        .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        .putHeader("Access-Control-Allow-Methods", "POST")
        .setStatusCode(200)
        .end(finalResponse.encodePrettily());
  }
}
