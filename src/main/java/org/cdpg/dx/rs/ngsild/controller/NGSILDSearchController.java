package org.cdpg.dx.rs.ngsild.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.HEADER_ALLOW_ORIGIN;
import static org.cdpg.dx.rs.audit.util.Constants.*;
import static org.cdpg.dx.rs.ngsild.util.Constants.*;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.*;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auditing.model.AuditLog;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.rs.audit.util.DataplaneAuditHelper;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;
import org.cdpg.dx.rs.ngsild.service.NGSILDService;
import org.cdpg.dx.rs.ngsild.util.Util;
import org.cdpg.dx.rs.validation.ngsild.NGSILDParamsValidator;
import org.cdpg.dx.validations.idhandler.GetIdFromBodyHandler;
import org.cdpg.dx.validations.idhandler.GetIdFromParams;
import org.cdpg.dx.validations.idvalidation.IdValidation;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessApplicableFilterHandlerNgsild;

public class NGSILDSearchController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDSearchController.class);
  private final ItemAccessApplicableFilterHandlerNgsild itemAccessApplicableFilterHandlerNgsild;
  private final URNGenerator urnGenerator;
  private final IdValidation idValidation;
  private final AuditingHandler auditingHandler;
  GetIdFromParams getIdFromParams = new GetIdFromParams();
  GetIdFromBodyHandler getIdFromBodyHandler = new GetIdFromBodyHandler();
  NGSILDParamsValidator ngsildParamsValidator;
  NGSILDService ngsildService;

  public NGSILDSearchController(
      NGSILDService ngsildService,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      int maxDaysSync,
      int maxDaysAsync,
      AuditingHandler auditingHandler) {
    this.ngsildService = ngsildService;
    this.itemAccessApplicableFilterHandlerNgsild =
        new ItemAccessApplicableFilterHandlerNgsild(controlPlaneDomain);
    this.idValidation = new IdValidation();
    this.ngsildParamsValidator = new NGSILDParamsValidator(maxDaysSync, maxDaysAsync);
    this.urnGenerator = urnGenerator;
    this.auditingHandler = auditingHandler;
  }

  @Override
  public void register(RouterBuilder builder) {
    builder
        .operation(GET_TEMPORAL_ENTITY_SEARCH)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromParams)
        .handler(itemAccessApplicableFilterHandlerNgsild)
        .handler(idValidation)
        .handler(context -> handleTemporalEntityDataSearch(context, true));
    builder
        .operation(POST_SPATIAL_TEMPORAL_COMPLEX_QUERY)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromBodyHandler)
        .handler(itemAccessApplicableFilterHandlerNgsild)
        .handler(idValidation)
        .handler(context -> handlePostTemporalEntityDataSearch(context, true));
    builder
        .operation(GET_SPATIAL_SEARCH)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromParams)
        .handler(itemAccessApplicableFilterHandlerNgsild)
        .handler(idValidation)
        .handler(context -> handleEntityAttributeDataSearch(context, false));
    builder
        .operation(POST_SPATIAL_COMPLEX_QUERY)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromBodyHandler)
        .handler(itemAccessApplicableFilterHandlerNgsild)
        .handler(idValidation)
        .handler(context -> handlePostEntityAttributeDataSearch(context, false));
  }

  private void handlePostEntityAttributeDataSearch(RoutingContext context, boolean isTemporal) {
    LOGGER.debug("Handling handlePostEntityAttributeDataSearch data query");
    String headersAcceptType =
        ngsildParamsValidator.validateAndSelectBestMediaType(context.request().getHeader("Accept"));
    LOGGER.warn("headersAcceptType : " + headersAcceptType);
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(context);
    /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    JsonObject bodyJson =
        context.body() != null && context.body().asJsonObject() != null
            ? context.body().asJsonObject()
            : new JsonObject();
    LOGGER.info("Info: request Json : " + bodyJson);
    JsonObject requestJson = bodyJson.copy();
    MultiMap params = context.request().params(true);
    MultiMap requestConvertedParam = Util.convertBodyToParams(bodyJson);
    LOGGER.trace("Info: Converted Params : " + requestConvertedParam);

    try {
      ngsildParamsValidator.validateQueryParamsEntities(requestConvertedParam);
      ngsildParamsValidator.validateQueryParamsPost(params);
      ngsildParamsValidator.isValidQueryWithFilters(requestConvertedParam, applicableFilter);
      // temporal params validation
      ngsildParamsValidator.validateTemporal(
          requestConvertedParam.get(NGSILDQUERY_TIMEREL),
          requestConvertedParam.get(NGSILDQUERY_TIMEAT),
          requestConvertedParam.get(NGSILDQUERY_ENDTIMEAT),
          requestConvertedParam.get(NGSILDQUERY_TIMEPROPERTY),
          false,
          isTemporal);
      // Validate geo Fields
      ngsildParamsValidator.validateGeometry(
          requestConvertedParam.get(NGSILDQUERY_GEOPROPERTY),
          requestConvertedParam.get(NGSILDQUERY_GEOMETRY),
          requestConvertedParam.get(NGSILDQUERY_COORDINATES));
      ngsildParamsValidator.validateQ(requestConvertedParam.get(NGSILDQUERY_Q));
      ngsildParamsValidator.validatePick(requestConvertedParam.get(NGSILDQUERY_PICK));
      ngsildParamsValidator.validateOmit(requestConvertedParam.get(NGSILDQUERY_OMIT));
      params.entries().forEach(e -> requestJson.put(e.getKey(), e.getValue()));
      LOGGER.debug("nsgildParamsValidator completed");
    } catch (DxBadRequestException e) {
      context.fail(e);
      return;
    }

    HttpServerResponse response = context.response();
    NGSILDQueryParams ngsildQueryParams = new NGSILDQueryParams(requestJson);

    if (ngsildQueryParams.isCount()) {
      ngsildService
          .getEntitiesAttributeSearchCount(ngsildQueryParams)
          .onSuccess(
              postEntitiesCount -> {
                AuditLog auditLog =
                    DataplaneAuditHelper.createAuditingLogs(
                        RoutingContextHelper.getItemMetaData(context),
                        RoutingContextHelper.getId(context),
                        RoutingContextHelper.getRequestPath(context),
                        "POST",
                        context.user().subject(),
                        NGSILD,
                        "consumer",
                        DOWNLOAD);
                RoutingContextHelper.setAuditingLog(context, auditLog);
                JsonObject result = new JsonObject();
                result.put("type", "CountResult");
                result.put("value", postEntitiesCount);
                response
                    .putHeader("Content-Type", headersAcceptType)
                    .putHeader(HEADER_ALLOW_ORIGIN, "*")
                    .putHeader(
                        "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                    .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    .putHeader(NGSILD_RESULTS_COUNT, String.valueOf(postEntitiesCount))
                    .setStatusCode(200)
                    .end(result.encode());
              })
          .onFailure(
              err -> {
                LOGGER.error("Count request failed: {}", err.getMessage(), err);
                context.fail(err);
              });
    } else {
      if (ngsildQueryParams.getPageFrom() + ngsildQueryParams.getPageSize() > 50000) {
        context.fail(
            new DxBadRequestException(
                "The combination of 'limit' and 'offset' must not exceed 50000"));
        return;
      }
      if (params.contains("format")) {
        String format = params.get("format");
        LOGGER.info("format param :: " + format);
        if (format != null
            && format.equalsIgnoreCase("simplified")
            && headersAcceptType.equalsIgnoreCase("application/json")) {
          LOGGER.info("simplified format selected ");
          ngsildService
              .getEntitiesAttributeSearchData(ngsildQueryParams)
              .onSuccess(
                  getEntityData -> {
                    AuditLog auditLog =
                        DataplaneAuditHelper.createAuditingLogs(
                            RoutingContextHelper.getItemMetaData(context),
                            RoutingContextHelper.getId(context),
                            RoutingContextHelper.getRequestPath(context),
                            "POST",
                            context.user().subject(),
                            NGSILD,
                            "consumer",
                            DOWNLOAD);
                    RoutingContextHelper.setAuditingLog(context, auditLog);
                    response
                        .putHeader("Content-Type", headersAcceptType)
                        .putHeader(HEADER_ALLOW_ORIGIN, "*")
                        .putHeader(
                            "Access-Control-Allow-Methods",
                            "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                        .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                        .putHeader(
                            NGSILD_RESULTS_COUNT, String.valueOf(getEntityData.getTotalHits()))
                        .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                        .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                        .setStatusCode(200)
                        /*.end(JsonObject.mapFrom(response).encode());*/
                        .end(getEntityData.getElasticsearchResponses().toString());
                  })
              .onFailure(
                  err -> {
                    LOGGER.error("Search request failed: {}", err.getMessage(), err);
                    context.fail(err);
                  });
        } else if (format != null
            && format.equalsIgnoreCase("simplified")
            && (headersAcceptType.equalsIgnoreCase("application/ld+json")
                || headersAcceptType.equalsIgnoreCase("application/geo+json"))) {
          ngsildService
              .getEntitiesAttributeSearchData(ngsildQueryParams)
              .onSuccess(
                  getEntityData -> {
                    JsonArray sanitizedResults = new JsonArray();
                    for (JsonObject entity : getEntityData.getElasticsearchResponses()) {
                      JsonObject cleaned = entity.copy();
                      cleaned.remove("@context");
                      sanitizedResults.add(cleaned);
                    }
                    AuditLog auditLog =
                        DataplaneAuditHelper.createAuditingLogs(
                            RoutingContextHelper.getItemMetaData(context),
                            RoutingContextHelper.getId(context),
                            RoutingContextHelper.getRequestPath(context),
                            "POST",
                            context.user().subject(),
                            NGSILD,
                            "consumer",
                            DOWNLOAD);
                    RoutingContextHelper.setAuditingLog(context, auditLog);
                    response
                        .putHeader("Content-Type", headersAcceptType)
                        .putHeader(HEADER_ALLOW_ORIGIN, "*")
                        .putHeader(
                            "Access-Control-Allow-Methods",
                            "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                        .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                        .putHeader(
                            NGSILD_RESULTS_COUNT, String.valueOf(getEntityData.getTotalHits()))
                        .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                        .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                        .setStatusCode(200)
                        .end(sanitizedResults.encodePrettily());
                  })
              .onFailure(
                  err -> {
                    LOGGER.error("Search request failed: {}", err.getMessage(), err);
                    context.fail(err);
                  });
        } else {
          LOGGER.error("invalid format param");
        }
      } else {
        ngsildService
            .getEntitiesAttributeSearchData(ngsildQueryParams)
            .onSuccess(
                getEntityData -> {
                  AuditLog auditLog =
                      DataplaneAuditHelper.createAuditingLogs(
                          RoutingContextHelper.getItemMetaData(context),
                          RoutingContextHelper.getId(context),
                          RoutingContextHelper.getRequestPath(context),
                          "POST",
                          context.user().subject(),
                          NGSILD,
                          "consumer",
                          DOWNLOAD);
                  RoutingContextHelper.setAuditingLog(context, auditLog);
                  response
                      .putHeader("Content-Type", headersAcceptType)
                      .putHeader(HEADER_ALLOW_ORIGIN, "*")
                      .putHeader(
                          "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                      .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                      .putHeader(NGSILD_RESULTS_COUNT, String.valueOf(getEntityData.getTotalHits()))
                      .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                      .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                      .setStatusCode(200)
                      /*.end(JsonObject.mapFrom(response).encode());*/
                      .end(getEntityData.getElasticsearchResponses().toString());
                })
            .onFailure(
                err -> {
                  LOGGER.error("Search request failed: {}", err.getMessage(), err);
                  context.fail(err);
                });
      }
    }
  }

  private void handleEntityAttributeDataSearch(RoutingContext routingContext, boolean isTemporal) {
    LOGGER.debug("Handling entities attribute GET data query");
    String headersAcceptType =
        ngsildParamsValidator.validateAndSelectBestMediaType(
            routingContext.request().getHeader("Accept"));
    LOGGER.warn("headersAcceptType :: " + headersAcceptType);
    MultiMap params = routingContext.request().params(true);
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(routingContext);
    /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    try {
      ngsildParamsValidator.validateQueryParamsEntities(params);
      ngsildParamsValidator.isValidQueryWithFilters(params, applicableFilter);

      // temporal params validation
      // TODO: refactor to make it more readable
      ngsildParamsValidator.validateTemporal(
          params.get(NGSILDQUERY_TIMEREL),
          params.get(NGSILDQUERY_TIMEAT),
          params.get(NGSILDQUERY_ENDTIMEAT),
          params.get(NGSILDQUERY_TIMEPROPERTY),
          false,
          isTemporal);

      // Validate geo Fields
      ngsildParamsValidator.validateGeometry(
          params.get(NGSILDQUERY_GEOPROPERTY),
          params.get(NGSILDQUERY_GEOMETRY),
          params.get(NGSILDQUERY_COORDINATES));

      ngsildParamsValidator.validateQ(params.get(NGSILDQUERY_Q));

      ngsildParamsValidator.validatePick(params.get(NGSILDQUERY_PICK));
      ngsildParamsValidator.validateOmit(params.get(NGSILDQUERY_OMIT));
      LOGGER.debug("nsgildParamsValidator completed");
    } catch (DxBadRequestException e) {
      routingContext.fail(e);
      return;
    }
    HttpServerResponse response = routingContext.response();
    NGSILDQueryParams ngsildQueryParams = new NGSILDQueryParams(params);
    if (ngsildQueryParams.isCount()) {
      ngsildService
          .getEntitiesAttributeSearchCount(ngsildQueryParams)
          .onSuccess(
              getTemporalEntityCount -> {
                AuditLog auditLog =
                    DataplaneAuditHelper.createAuditingLogs(
                        RoutingContextHelper.getItemMetaData(routingContext),
                        RoutingContextHelper.getId(routingContext),
                        RoutingContextHelper.getRequestPath(routingContext),
                        "GET",
                        routingContext.user().subject(),
                        NGSILD,
                        "consumer",
                        DOWNLOAD);
                RoutingContextHelper.setAuditingLog(routingContext, auditLog);
                JsonObject result = new JsonObject();
                result.put("type", "CountResult");
                result.put("value", getTemporalEntityCount);
                response
                    .putHeader("Content-Type", headersAcceptType)
                    .putHeader(HEADER_ALLOW_ORIGIN, "*")
                    .putHeader(
                        "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                    .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    .putHeader(NGSILD_RESULTS_COUNT, String.valueOf(getTemporalEntityCount))
                    .setStatusCode(200)
                    .end(result.encode());
              })
          .onFailure(
              err -> {
                LOGGER.error("Count request failed: {}", err.getMessage(), err);
                routingContext.fail(err);
              });
    } else {
      if (ngsildQueryParams.getPageFrom() + ngsildQueryParams.getPageSize() > 50000) {
        routingContext.fail(
            new DxBadRequestException(
                "The combination of 'limit' and 'offset' must not exceed 50000"));
        return;
      }
      if (params.contains("format")) {
        String format = params.get("format");
        LOGGER.info("format param :: " + format);
        if (format != null
            && format.equalsIgnoreCase("simplified")
            && headersAcceptType.equalsIgnoreCase("application/json")) {
          LOGGER.info("simplified format selected ");
          ngsildService
              .getEntitiesAttributeSearchData(ngsildQueryParams)
              .onSuccess(
                  getTemporalEntityData -> {
                    AuditLog auditLog =
                        DataplaneAuditHelper.createAuditingLogs(
                            RoutingContextHelper.getItemMetaData(routingContext),
                            RoutingContextHelper.getId(routingContext),
                            RoutingContextHelper.getRequestPath(routingContext),
                            "GET",
                            routingContext.user().subject(),
                            NGSILD,
                            "consumer",
                            DOWNLOAD);
                    RoutingContextHelper.setAuditingLog(routingContext, auditLog);
                    response
                        .putHeader("Content-Type", headersAcceptType)
                        .putHeader(HEADER_ALLOW_ORIGIN, "*")
                        .putHeader(
                            "Access-Control-Allow-Methods",
                            "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                        .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                        .putHeader(
                            NGSILD_RESULTS_COUNT,
                            String.valueOf(getTemporalEntityData.getTotalHits()))
                        .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                        .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                        .putHeader(NGSILD_LINK, "Link of context")
                        .setStatusCode(200)
                        /*.end(JsonObject.mapFrom(response).encode());*/
                        .end(getTemporalEntityData.getElasticsearchResponses().toString());
                    /*ResponseBuilder.sendSuccess(
                    routingContext,
                    getTemporalEntityData.getElasticsearchResponses(),
                    getTemporalEntityData.getPaginationInfo(),
                    urnGenerator);*/
                  })
              .onFailure(
                  err -> {
                    LOGGER.error("Search request failed: {}", err.getMessage(), err);
                    routingContext.fail(err);
                  });
        } else if (format != null
            && format.equalsIgnoreCase("simplified")
            && (headersAcceptType.equalsIgnoreCase("application/ld+json")
                || headersAcceptType.equalsIgnoreCase("application/geo+json"))) {
          LOGGER.info("concise format selected");
          ngsildService
              .getEntitiesAttributeSearchData(ngsildQueryParams)
              .onSuccess(
                  getTemporalEntityData -> {
                    JsonArray sanitizedResults = new JsonArray();
                    for (JsonObject entity : getTemporalEntityData.getElasticsearchResponses()) {
                      JsonObject cleaned = entity.copy();
                      cleaned.remove("@context");
                      sanitizedResults.add(cleaned);
                    }
                    AuditLog auditLog =
                        DataplaneAuditHelper.createAuditingLogs(
                            RoutingContextHelper.getItemMetaData(routingContext),
                            RoutingContextHelper.getId(routingContext),
                            RoutingContextHelper.getRequestPath(routingContext),
                            "GET",
                            routingContext.user().subject(),
                            NGSILD,
                            "consumer",
                            DOWNLOAD);
                    RoutingContextHelper.setAuditingLog(routingContext, auditLog);
                    response
                        .putHeader("Content-Type", headersAcceptType)
                        .putHeader(HEADER_ALLOW_ORIGIN, "*")
                        .putHeader(
                            "Access-Control-Allow-Methods",
                            "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                        .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                        .putHeader(
                            NGSILD_RESULTS_COUNT,
                            String.valueOf(getTemporalEntityData.getTotalHits()))
                        .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                        .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                        // .putHeader(NGSILD_LINK, "Link of context")
                        .setStatusCode(200)
                        .end(sanitizedResults.encodePrettily());
                  })
              .onFailure(
                  err -> {
                    LOGGER.error("Search request failed: {}", err.getMessage(), err);
                    routingContext.fail(err);
                  });
        } else {
          LOGGER.error("invalid format param");
        }
      } else {
        LOGGER.info("simplified format selected ");
        ngsildService
            .getEntitiesAttributeSearchData(ngsildQueryParams)
            .onSuccess(
                getTemporalEntityData -> {
                  AuditLog auditLog =
                      DataplaneAuditHelper.createAuditingLogs(
                          RoutingContextHelper.getItemMetaData(routingContext),
                          RoutingContextHelper.getId(routingContext),
                          RoutingContextHelper.getRequestPath(routingContext),
                          "GET",
                          routingContext.user().subject(),
                          NGSILD,
                          "consumer",
                          DOWNLOAD);
                  RoutingContextHelper.setAuditingLog(routingContext, auditLog);
                  response
                      .putHeader("Content-Type", headersAcceptType)
                      .putHeader(HEADER_ALLOW_ORIGIN, "*")
                      .putHeader(
                          "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                      .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                      .putHeader(
                          NGSILD_RESULTS_COUNT,
                          String.valueOf(getTemporalEntityData.getTotalHits()))
                      .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                      .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                      // .putHeader(NGSILD_LINK, "Link of context")
                      .setStatusCode(200)
                      .end(getTemporalEntityData.getElasticsearchResponses().toString());
                })
            .onFailure(
                err -> {
                  LOGGER.error("Search request failed: {}", err.getMessage(), err);
                  routingContext.fail(err);
                });
      }
    }
  }

  private void handlePostTemporalEntityDataSearch(RoutingContext context, boolean isTemporal) {
    LOGGER.debug("Handling Temporal entities POST data query");
    String headersAcceptType =
        ngsildParamsValidator.validateAndSelectBestMediaType(context.request().getHeader("Accept"));
    LOGGER.warn("headersAcceptType :: " + headersAcceptType);
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(context);
    /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    JsonObject bodyJson =
        context.body() != null && context.body().asJsonObject() != null
            ? context.body().asJsonObject()
            : new JsonObject();
    LOGGER.info("Info: request Json :: " + bodyJson);
    JsonObject requestJson = bodyJson.copy();
    MultiMap params = context.request().params(true);
    MultiMap requestConvertedParam = Util.convertBodyToParams(bodyJson);
    LOGGER.trace("Info: Converted Params :: " + requestConvertedParam);

    try {
      ngsildParamsValidator.validateQueryParamsTemporalEntities(requestConvertedParam);
      ngsildParamsValidator.validateQueryParamsPost(params);
      ngsildParamsValidator.isValidQueryWithFilters(requestConvertedParam, applicableFilter);
      // temporal params validation
      ngsildParamsValidator.validateTemporal(
          requestConvertedParam.get(NGSILDQUERY_TIMEREL),
          requestConvertedParam.get(NGSILDQUERY_TIMEAT),
          requestConvertedParam.get(NGSILDQUERY_ENDTIMEAT),
          requestConvertedParam.get(NGSILDQUERY_TIMEPROPERTY),
          false,
          isTemporal);
      // Validate geo Fields
      ngsildParamsValidator.validateGeometry(
          requestConvertedParam.get(NGSILDQUERY_GEOPROPERTY),
          requestConvertedParam.get(NGSILDQUERY_GEOMETRY),
          requestConvertedParam.get(NGSILDQUERY_COORDINATES));
      ngsildParamsValidator.validateQ(requestConvertedParam.get(NGSILDQUERY_Q));
      ngsildParamsValidator.validatePick(requestConvertedParam.get(NGSILDQUERY_PICK));
      ngsildParamsValidator.validateOmit(requestConvertedParam.get(NGSILDQUERY_OMIT));
      params.entries().forEach(e -> requestJson.put(e.getKey(), e.getValue()));
      LOGGER.debug("nsgildParamsValidator completed");
    } catch (DxBadRequestException e) {
      context.fail(e);
      return;
    }

    HttpServerResponse response = context.response();
    NGSILDQueryParams ngsildQueryParams = new NGSILDQueryParams(requestJson);

    if (ngsildQueryParams.isCount()) {
      ngsildService
          .getTemporalSearchCount(ngsildQueryParams)
          .onSuccess(
              postTemporalEntitiesCount -> {
                AuditLog auditLog =
                    DataplaneAuditHelper.createAuditingLogs(
                        RoutingContextHelper.getItemMetaData(context),
                        RoutingContextHelper.getId(context),
                        RoutingContextHelper.getRequestPath(context),
                        "POST",
                        context.user().subject(),
                        NGSILD,
                        "consumer",
                        DOWNLOAD);
                RoutingContextHelper.setAuditingLog(context, auditLog);
                JsonObject result = new JsonObject();
                result.put("type", "CountResult");
                result.put("value", postTemporalEntitiesCount);
                response
                    .putHeader("Content-Type", headersAcceptType)
                    .putHeader(HEADER_ALLOW_ORIGIN, "*")
                    .putHeader(
                        "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                    .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    .putHeader(NGSILD_RESULTS_COUNT, String.valueOf(postTemporalEntitiesCount))
                    .setStatusCode(200)
                    .end(result.encode());
              })
          .onFailure(
              err -> {
                LOGGER.error("Count request failed: {}", err.getMessage(), err);
                context.fail(err);
              });
    } else {
      if (ngsildQueryParams.getPageFrom() + ngsildQueryParams.getPageSize() > 50000) {
        context.fail(
            new DxBadRequestException(
                "The combination of 'limit' and 'offset' must not exceed 50000"));
        return;
      }
      if (params.contains("format")) {
        String format = params.get("format");
        LOGGER.info("format param :: " + format);
        if (format != null
            && format.equalsIgnoreCase("simplified")
            && headersAcceptType.equalsIgnoreCase("application/json")) {
          ngsildService
              .getTemporalSearchData(ngsildQueryParams)
              .onSuccess(
                  getTemporalEntityData -> {
                    AuditLog auditLog =
                        DataplaneAuditHelper.createAuditingLogs(
                            RoutingContextHelper.getItemMetaData(context),
                            RoutingContextHelper.getId(context),
                            RoutingContextHelper.getRequestPath(context),
                            "POST",
                            context.user().subject(),
                            NGSILD,
                            "consumer",
                            DOWNLOAD);
                    RoutingContextHelper.setAuditingLog(context, auditLog);
                    response
                        .putHeader("Content-Type", headersAcceptType)
                        .putHeader(HEADER_ALLOW_ORIGIN, "*")
                        .putHeader(
                            "Access-Control-Allow-Methods",
                            "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                        .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                        .putHeader(
                            NGSILD_RESULTS_COUNT,
                            String.valueOf(getTemporalEntityData.getTotalHits()))
                        .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                        .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                        .setStatusCode(200)
                        /*.end(JsonObject.mapFrom(response).encode());*/
                        .end(getTemporalEntityData.getElasticsearchResponses().toString());
                  })
              .onFailure(
                  err -> {
                    LOGGER.error("Search request failed: {}", err.getMessage(), err);
                    context.fail(err);
                  });
        } else if (format != null
            && format.equalsIgnoreCase("simplified")
            && (headersAcceptType.equalsIgnoreCase("application/ld+json")
                || headersAcceptType.equalsIgnoreCase("application/geo+json"))) {
          ngsildService
              .getTemporalSearchData(ngsildQueryParams)
              .onSuccess(
                  getTemporalEntityData -> {
                    JsonArray sanitizedResults = new JsonArray();
                    for (JsonObject entity : getTemporalEntityData.getElasticsearchResponses()) {
                      JsonObject cleaned = entity.copy();
                      cleaned.remove("@context");
                      sanitizedResults.add(cleaned);
                    }
                    AuditLog auditLog =
                        DataplaneAuditHelper.createAuditingLogs(
                            RoutingContextHelper.getItemMetaData(context),
                            RoutingContextHelper.getId(context),
                            RoutingContextHelper.getRequestPath(context),
                            "POST",
                            context.user().subject(),
                            NGSILD,
                            "consumer",
                            DOWNLOAD);
                    RoutingContextHelper.setAuditingLog(context, auditLog);
                    response
                        .putHeader("Content-Type", headersAcceptType)
                        .putHeader(HEADER_ALLOW_ORIGIN, "*")
                        .putHeader(
                            "Access-Control-Allow-Methods",
                            "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                        .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                        .putHeader(
                            NGSILD_RESULTS_COUNT,
                            String.valueOf(getTemporalEntityData.getTotalHits()))
                        .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                        .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                        .setStatusCode(200)
                        .end(sanitizedResults.encodePrettily());
                  })
              .onFailure(
                  err -> {
                    LOGGER.error("Search request failed: {}", err.getMessage(), err);
                    context.fail(err);
                  });
        } else {
          LOGGER.error("invalid format param");
        }
      } else {
        ngsildService
            .getTemporalSearchData(ngsildQueryParams)
            .onSuccess(
                getTemporalEntityData -> {
                  AuditLog auditLog =
                      DataplaneAuditHelper.createAuditingLogs(
                          RoutingContextHelper.getItemMetaData(context),
                          RoutingContextHelper.getId(context),
                          RoutingContextHelper.getRequestPath(context),
                          "POST",
                          context.user().subject(),
                          NGSILD,
                          "consumer",
                          DOWNLOAD);
                  RoutingContextHelper.setAuditingLog(context, auditLog);
                  response
                      .putHeader("Content-Type", headersAcceptType)
                      .putHeader(HEADER_ALLOW_ORIGIN, "*")
                      .putHeader(
                          "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                      .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                      .putHeader(
                          NGSILD_RESULTS_COUNT,
                          String.valueOf(getTemporalEntityData.getTotalHits()))
                      .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                      .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                      .setStatusCode(200)
                      /*.end(JsonObject.mapFrom(response).encode());*/
                      .end(getTemporalEntityData.getElasticsearchResponses().toString());
                })
            .onFailure(
                err -> {
                  LOGGER.error("Search request failed: {}", err.getMessage(), err);
                  context.fail(err);
                });
      }
    }
  }

  private void handleTemporalEntityDataSearch(RoutingContext routingContext, boolean isTemporal) {
    LOGGER.debug("Handling Temporal entities GET data query");

    MultiMap params = routingContext.request().params(true);
    /*params.add(NGSILD_LINK, routingContext.request().getHeader(NGSILD_LINK));*/

    String headersAcceptType =
        ngsildParamsValidator.validateAndSelectBestMediaType(
            routingContext.request().getHeader("Accept"));
    LOGGER.warn("headersAcceptType :: " + headersAcceptType);
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(routingContext);
    /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    try {
      ngsildParamsValidator.validateQueryParamsTemporalEntities(params);
      ngsildParamsValidator.isValidQueryWithFilters(params, applicableFilter);
      ngsildParamsValidator.validateAggrs(
          params.get(NGSILD_OPTIONS), params.get(NGSILDQUERY_AGGR_METHODS));
      // temporal params validation
      // TODO: refactor to make it more readable
      ngsildParamsValidator.validateTemporal(
          params.get(NGSILDQUERY_TIMEREL),
          params.get(NGSILDQUERY_TIMEAT),
          params.get(NGSILDQUERY_ENDTIMEAT),
          params.get(NGSILDQUERY_TIMEPROPERTY),
          false,
          isTemporal);

      // Validate geo Fields
      ngsildParamsValidator.validateGeometry(
          params.get(NGSILDQUERY_GEOPROPERTY),
          params.get(NGSILDQUERY_GEOMETRY),
          params.get(NGSILDQUERY_COORDINATES));

      ngsildParamsValidator.validateQ(params.get(NGSILDQUERY_Q));

      ngsildParamsValidator.validatePick(params.get(NGSILDQUERY_PICK));
      ngsildParamsValidator.validateOmit(params.get(NGSILDQUERY_OMIT));
      LOGGER.debug("nsgildParamsValidator completed");
    } catch (DxBadRequestException e) {
      routingContext.fail(e);
      return;
    }
    HttpServerResponse response = routingContext.response();
    NGSILDQueryParams ngsildQueryParams = new NGSILDQueryParams(params);
    if (ngsildQueryParams.isCount()) {
      ngsildService
          .getTemporalSearchCount(ngsildQueryParams)
          .onSuccess(
              getTemporalEntityCount -> {
                AuditLog auditLog =
                    DataplaneAuditHelper.createAuditingLogs(
                        RoutingContextHelper.getItemMetaData(routingContext),
                        RoutingContextHelper.getId(routingContext),
                        RoutingContextHelper.getRequestPath(routingContext),
                        "GET",
                        routingContext.user().subject(),
                        NGSILD,
                        "consumer",
                        DOWNLOAD);
                RoutingContextHelper.setAuditingLog(routingContext, auditLog);
                JsonObject result = new JsonObject();
                result.put("type", "CountResult");
                result.put("value", getTemporalEntityCount);
                response
                    .putHeader("Content-Type", headersAcceptType)
                    .putHeader(HEADER_ALLOW_ORIGIN, "*")
                    .putHeader(
                        "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                    .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    .putHeader(NGSILD_RESULTS_COUNT, String.valueOf(getTemporalEntityCount))
                    .setStatusCode(200)
                    .end(result.encode());
              })
          .onFailure(
              err -> {
                LOGGER.error("Count request failed: {}", err.getMessage(), err);
                routingContext.fail(err);
              });
    } else {
      if (ngsildQueryParams.getPageFrom() + ngsildQueryParams.getPageSize() > 50000) {
        routingContext.fail(
            new DxBadRequestException(
                "The combination of 'limit' and 'offset' must not exceed 50000"));
        return;
      }
      if (params.contains("format")) {
        String format = params.get("format");
        LOGGER.info("format param ::: " + format);
        if (format != null
            && format.equalsIgnoreCase("simplified")
            && headersAcceptType.equalsIgnoreCase("application/json")) {
          LOGGER.info("simplified format selected ");
          ngsildService
              .getTemporalSearchData(ngsildQueryParams)
              .onSuccess(
                  getTemporalEntityData -> {
                    AuditLog auditLog =
                        DataplaneAuditHelper.createAuditingLogs(
                            RoutingContextHelper.getItemMetaData(routingContext),
                            RoutingContextHelper.getId(routingContext),
                            RoutingContextHelper.getRequestPath(routingContext),
                            "GET",
                            routingContext.user().subject(),
                            NGSILD,
                            "consumer",
                            DOWNLOAD);
                    RoutingContextHelper.setAuditingLog(routingContext, auditLog);

                    response
                        .putHeader("Content-Type", headersAcceptType)
                        .putHeader(HEADER_ALLOW_ORIGIN, "*")
                        .putHeader(
                            "Access-Control-Allow-Methods",
                            "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                        .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                        .putHeader(
                            NGSILD_RESULTS_COUNT,
                            String.valueOf(getTemporalEntityData.getTotalHits()))
                        .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                        .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                        // .putHeader(NGSILD_LINK, "Link of context")
                        .setStatusCode(200);
                    // Return raw aggregations if requested, otherwise original hits
                    {
                      String opts = ngsildQueryParams.getOptions();
                      /*String format = ngsildQueryParams.getFormat();*/
                      boolean wantsAggregated = false;
                      if (opts != null && opts.toLowerCase().contains("aggregatedvalues"))
                        wantsAggregated = true;
                      /*if (format != null && format.toLowerCase().contains("aggregatedvalues"))
                      wantsAggregated = true;*/
                      if (wantsAggregated) {
                        JsonObject aggs = ElasticsearchResponse.getAggregations();
                        if (aggs == null) aggs = new JsonObject();
                        if (aggs.containsKey("results")
                            && aggs.getValue("results") instanceof JsonObject) {
                          aggs = aggs.getJsonObject("results");
                        }
                        response.end(aggs.encode());
                      } else {
                        response.end(getTemporalEntityData.getElasticsearchResponses().toString());
                      }
                    }
                  })
              .onFailure(
                  err -> {
                    LOGGER.error("Search request failed: {}", err.getMessage(), err);
                    routingContext.fail(err);
                  });
        } else if (format != null
            && format.equalsIgnoreCase("simplified")
            && (headersAcceptType.equalsIgnoreCase("application/ld+json")
                || headersAcceptType.equalsIgnoreCase("application/geo+json"))) {
          LOGGER.info("concise format selected");
          ngsildService
              .getTemporalSearchData(ngsildQueryParams)
              .onSuccess(
                  getTemporalEntityData -> {
                    AuditLog auditLog =
                        DataplaneAuditHelper.createAuditingLogs(
                            RoutingContextHelper.getItemMetaData(routingContext),
                            RoutingContextHelper.getId(routingContext),
                            RoutingContextHelper.getRequestPath(routingContext),
                            "GET",
                            routingContext.user().subject(),
                            NGSILD,
                            "consumer",
                            DOWNLOAD);
                    RoutingContextHelper.setAuditingLog(routingContext, auditLog);

                    response
                        .putHeader("Content-Type", headersAcceptType)
                        .putHeader(HEADER_ALLOW_ORIGIN, "*")
                        .putHeader(
                            "Access-Control-Allow-Methods",
                            "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                        .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                        .putHeader(
                            NGSILD_RESULTS_COUNT,
                            String.valueOf(getTemporalEntityData.getTotalHits()))
                        .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                        .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                        // .putHeader(NGSILD_LINK, "Link of context")
                        .setStatusCode(200);
                    // Return raw aggregations if requested, otherwise original hits
                    {
                      String opts = ngsildQueryParams.getOptions();
                      /*String format = ngsildQueryParams.getFormat();*/
                      boolean wantsAggregated = false;
                      if (opts != null && opts.toLowerCase().contains("aggregatedvalues"))
                        wantsAggregated = true;
                      /*if (format != null && format.toLowerCase().contains("aggregatedvalues"))
                      wantsAggregated = true;*/
                      if (wantsAggregated) {
                        JsonObject aggs = ElasticsearchResponse.getAggregations();
                        if (aggs == null) aggs = new JsonObject();
                        if (aggs.containsKey("results")
                            && aggs.getValue("results") instanceof JsonObject) {
                          aggs = aggs.getJsonObject("results");
                        }
                        response.end(aggs.encode());
                      } else {
                        JsonArray sanitizedResults = new JsonArray();
                        for (JsonObject entity :
                            getTemporalEntityData.getElasticsearchResponses()) {
                          JsonObject cleaned = entity.copy();
                          cleaned.remove("@context");
                          sanitizedResults.add(cleaned);
                        }
                        response.end(sanitizedResults.encodePrettily());
                      }
                    }
                  })
              .onFailure(
                  err -> {
                    LOGGER.error("Search request failed: {}", err.getMessage(), err);
                    routingContext.fail(err);
                  });

        } else {
          LOGGER.error("invalid format param");
        }
      } else {
        LOGGER.info("simplified format selected");

        ngsildService
            .getTemporalSearchData(ngsildQueryParams)
            .onSuccess(
                getTemporalEntityData -> {
                  AuditLog auditLog =
                      DataplaneAuditHelper.createAuditingLogs(
                          RoutingContextHelper.getItemMetaData(routingContext),
                          RoutingContextHelper.getId(routingContext),
                          RoutingContextHelper.getRequestPath(routingContext),
                          "GET",
                          routingContext.user().subject(),
                          NGSILD,
                          "consumer",
                          DOWNLOAD);
                  RoutingContextHelper.setAuditingLog(routingContext, auditLog);

                  response
                      .putHeader("Content-Type", headersAcceptType)
                      .putHeader(HEADER_ALLOW_ORIGIN, "*")
                      .putHeader(
                          "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                      .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                      .putHeader(
                          NGSILD_RESULTS_COUNT,
                          String.valueOf(getTemporalEntityData.getTotalHits()))
                      .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                      .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                      // .putHeader(NGSILD_LINK, "Link of context")
                      .setStatusCode(200);
                  // Return raw aggregations if requested, otherwise original hits
                  {
                    String opts = ngsildQueryParams.getOptions();
                    /*String format = ngsildQueryParams.getFormat();*/
                    boolean wantsAggregated = false;
                    if (opts != null && opts.toLowerCase().contains("aggregatedvalues"))
                      wantsAggregated = true;
                    /*if (format != null && format.toLowerCase().contains("aggregatedvalues"))
                    wantsAggregated = true;*/
                    if (wantsAggregated) {
                      JsonObject aggs = ElasticsearchResponse.getAggregations();
                      if (aggs == null) aggs = new JsonObject();
                      if (aggs.containsKey("results")
                          && aggs.getValue("results") instanceof JsonObject) {
                        aggs = aggs.getJsonObject("results");
                      }
                      response.end(aggs.encode());
                    } else {
                      response.end(getTemporalEntityData.getElasticsearchResponses().toString());
                    }
                  }
                })
            .onFailure(
                err -> {
                  LOGGER.error("Search request failed: {}", err.getMessage(), err);
                  routingContext.fail(err);
                });
      }
    }
  }
}
