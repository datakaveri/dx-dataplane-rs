package org.cdpg.dx.rs.ngsilddatapublish.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.HEADER_ALLOW_ORIGIN;
import static org.cdpg.dx.databroker.util.Constants.*;
import static org.cdpg.dx.rs.audit.util.Constants.*;
import static org.cdpg.dx.rs.ngsilddatapublish.util.Constants.*;

import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auditing.model.AuditLog;
import org.cdpg.dx.auth.authorization.handler.AuthorizationHandler;
import org.cdpg.dx.auth.authorization.model.DxRole;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.rs.audit.util.DataplaneAuditHelper;
import org.cdpg.dx.rs.ngsilddatapublish.service.NGSILDDataPublishService;
import org.cdpg.dx.common.validations.idhandler.GetIdForIngestionEntityHandler;
import org.cdpg.dx.common.validations.idhandler.GetIdFromPathHandler;
import org.cdpg.dx.validations.idvalidation.IdValidation;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessDataPublishHandler;
import org.cdpg.dx.validations.provider.ProviderDelegateValidationHandler;

public class NGSILDDataPublishController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDDataPublishController.class);
  private final IdValidation idValidation;
  private final GetIdForIngestionEntityHandler getIdForIngestionEntityHandler;
  private final GetIdFromPathHandler getIdFromPathHandler;
  private final ItemAccessDataPublishHandler itemAccessDataPublishHandler;
  private final ProviderDelegateValidationHandler providerDelegateValidationHandler;
  private NGSILDDataPublishService ngsildDataPublishService;
  private URNGenerator urnGenerator;
  private AuditingHandler auditingHandler;

  public NGSILDDataPublishController(
      NGSILDDataPublishService ngsildDataPublishService,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      AuditingHandler auditingHandler) {
    this.auditingHandler = auditingHandler;
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
        .handler(context -> handleDataPublishAlias(context));

    builder
        .operation(POST_NGSILD_ENTITY_PUBLISH_ALIAS)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(AuthorizationHandler.forRoles(DxRole.PROVIDER, DxRole.DELEGATE))
        .handler(itemAccessDataPublishHandler)
        .handler(providerDelegateValidationHandler)
        .handler(idValidation)
        .handler(context -> handleDataPublishAlias(context));
  }

  private void handleDataPublish(RoutingContext context) {
    LOGGER.info("Handling NGSILD Data Publish Request");
    JsonArray requestJson = context.body().asJsonArray();
    HttpServerResponse response = context.response();
    String id = RoutingContextHelper.getId(context);
    ngsildDataPublishService
        .publishData(requestJson, id)
        .onSuccess(
            v -> {
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
            })
        .onFailure(context::fail);
  }

  private void handleDataPublishAlias(RoutingContext context) {
    LOGGER.info("Handling NGSI-LD Data Publish Request Alias");
    JsonArray requestJson = context.body().asJsonArray();
    HttpServerResponse response = context.response();
    String id = RoutingContextHelper.getId(context);
    ngsildDataPublishService
        .publishDataOnSeek(requestJson, id)
        .onSuccess(
            v -> {
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
            })
        .onFailure(context::fail);
  }
}
