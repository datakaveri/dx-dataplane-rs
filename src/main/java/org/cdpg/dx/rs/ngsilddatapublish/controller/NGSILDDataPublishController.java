package org.cdpg.dx.rs.ngsilddatapublish.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.HEADER_ALLOW_ORIGIN;
import static org.cdpg.dx.databroker.util.Constants.*;
import static org.cdpg.dx.rs.audit.util.Constants.*;
import static org.cdpg.dx.rs.ngsilddatapublish.util.Constants.*;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auditing.model.AuditLog;
import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auth.authorization.handler.AuthorizationHandler;
import org.cdpg.dx.auth.authorization.model.DxRole;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.common.validations.idhandler.GetIdForIngestionEntityHandler;
import org.cdpg.dx.common.validations.idhandler.GetIdFromPathHandler;
import org.cdpg.dx.rs.audit.util.DataplaneAuditHelper;
import org.cdpg.dx.rs.ngsilddatapublish.service.NGSILDDataPublishService;
import org.cdpg.dx.validations.idvalidation.IdValidation;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessDataPublishHandler;
import org.cdpg.dx.validations.provider.ProviderDelegateValidationHandler;

public class NGSILDDataPublishController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDDataPublishController.class);
  private final int chunkMaxItems;
  private final int chunkMaxBytes;
  private final IdValidation idValidation;
  private final GetIdForIngestionEntityHandler getIdForIngestionEntityHandler;
  private final GetIdFromPathHandler getIdFromPathHandler;
  private final ItemAccessDataPublishHandler itemAccessDataPublishHandler;
  private final ProviderDelegateValidationHandler providerDelegateValidationHandler;
  private final AppIdItemAccessHandler appIdItemAccessHandler;
  private NGSILDDataPublishService ngsildDataPublishService;
  private URNGenerator urnGenerator;
  private AuditingHandler auditingHandler;

  public NGSILDDataPublishController(
      NGSILDDataPublishService ngsildDataPublishService,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      AuditingHandler auditingHandler,
      AppIdItemAccessHandler appIdItemAccessHandler,
      int chunkMaxItems,
      int chunkMaxBytes) {
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
    this.appIdItemAccessHandler = appIdItemAccessHandler;
  }

  @Override
  public void register(RouterBuilder builder) {
    builder
        .operation(POST_NGSILD_ENTITY_PUBLISH)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdForIngestionEntityHandler)
        .handler(AuthorizationHandler.forRoles(DxRole.PROVIDER, DxRole.DELEGATE))
        .handler(appIdItemAccessHandler)
        .handler(itemAccessDataPublishHandler)
        .handler(providerDelegateValidationHandler)
        .handler(idValidation)
        .handler(context -> handleDataPublish(context));
    builder
        .operation(POST_NGSILD_ENTITY_PUBLISH_ONSEEK)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(AuthorizationHandler.forRoles(DxRole.PROVIDER, DxRole.DELEGATE))
        .handler(appIdItemAccessHandler)
        .handler(itemAccessDataPublishHandler)
        .handler(providerDelegateValidationHandler)
        .handler(idValidation)
        .handler(this::handleDataPublishOnSeek);

    builder
        .operation(POST_NGSILD_ENTITY_PUBLISH_ALIAS)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(AuthorizationHandler.forRoles(DxRole.PROVIDER, DxRole.DELEGATE))
        .handler(appIdItemAccessHandler)
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
    streamRequestToBufferAndUpload(context, id);
  }

  private void streamRequestToBufferAndUpload(RoutingContext context, String id) {
    String contentType = context.request().getHeader("Content-Type");
    Buffer bufferedBody = context.body() != null ? context.body().buffer() : null;
    if (bufferedBody != null) {
      if (bufferedBody.length() == 0) {
        context.fail(new DxBadRequestException("Empty request body"));
        return;
      }
      uploadBufferAndRespond(context, id, bufferedBody, contentType);
      return;
    }

    if (context.request().isEnded()) {
      context.fail(new DxBadRequestException("Empty request body"));
      return;
    }

    try {
      Path tempFile = Files.createTempFile("onseek-upload-", ".tmp");
      context
          .request()
          .handler(
              buffer -> {
                try {
                  Files.write(tempFile, buffer.getBytes(), StandardOpenOption.APPEND);
                } catch (Exception e) {
                  context.fail(e);
                }
              });
      context
          .request()
          .endHandler(
              ignored -> {
                try {
                  long fileSize = Files.size(tempFile);
                  if (fileSize == 0) {
                    Files.deleteIfExists(tempFile);
                    context.fail(new DxBadRequestException("Empty request body"));
                    return;
                  }
                  uploadFileAndRespond(context, id, tempFile, contentType);
                } catch (Exception e) {
                  try {
                    Files.deleteIfExists(tempFile);
                  } catch (Exception ex) {
                    LOGGER.warn("Failed to delete temp file: {}", ex.getMessage());
                  }
                  context.fail(e);
                }
              })
          .exceptionHandler(
              err -> {
                try {
                  Files.deleteIfExists(tempFile);
                } catch (Exception ex) {
                  LOGGER.warn("Failed to delete temp file: {}", ex.getMessage());
                }
                context.fail(err);
              });
      context.request().resume();
    } catch (Exception e) {
      context.fail(e);
    }
  }

  private void uploadBufferAndRespond(
      RoutingContext context, String id, Buffer payload, String contentType) {
    Buffer data = payload.copy();
    LOGGER.info("On-seek payload ready for upload id {} sizeBytes={}", id, data.length());
    ngsildDataPublishService
        .uploadFileToCloudAndPublishMetadata(data, id, contentType)
        .map(
            ignored -> {
              LOGGER.info("On-seek raw upload complete for id {}", id);
              return ignored;
            })
        .onSuccess(
            v -> {
              respondSuccess(context, context.response(), id);
            })
        .onFailure(
            err -> {
              context.fail(err);
            });
  }

  private void uploadFileAndRespond(
      RoutingContext context, String id, Path filePath, String contentType) {
    LOGGER.info("On-seek file ready for upload id {} sizeBytes={}", id, filePath.toFile().length());
    ngsildDataPublishService
        .uploadFileToCloudAndPublishMetadata(filePath, id, contentType)
        .map(
            ignored -> {
              LOGGER.info("On-seek raw upload complete for id {}", id);
              return ignored;
            })
        .onSuccess(
            v -> {
              try {
                Files.deleteIfExists(filePath);
              } catch (Exception e) {
                LOGGER.warn("Failed to delete temp file {}: {}", filePath, e.getMessage());
              }
              respondSuccess(context, context.response(), id);
            })
        .onFailure(
            err -> {
              try {
                Files.deleteIfExists(filePath);
              } catch (Exception e) {
                LOGGER.warn("Failed to delete temp file {}: {}", filePath, e.getMessage());
              }
              context.fail(err);
            });
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
