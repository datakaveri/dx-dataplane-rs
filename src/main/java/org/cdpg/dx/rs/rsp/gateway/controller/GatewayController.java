package org.cdpg.dx.rs.rsp.gateway.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;
import static org.cdpg.dx.apiserver.config.ApiConstants.IUDX_SEARCH_TYPE;
import static org.cdpg.dx.rs.audit.util.Constants.DOWNLOAD;
import static org.cdpg.dx.rs.audit.util.Constants.GATEWAY;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.*;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_ATTRIBUTE;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_COORDINATES;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_ENDTIMEAT;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_GEOMETRY;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_GEOQ;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_GEOREL;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_Q;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_TEMPORALQ;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_TIMEAT;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_TIMEPROPERTY;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_TIMEREL;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILD_OPTIONS;
import static org.cdpg.dx.rs.rsp.gateway.controller.Config2.*;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.apiserver.config.ApiConstants;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auditing.model.AuditLog;
import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auth.authorization.handler.AuthorizationHandler;
import org.cdpg.dx.auth.model.Scopes;
import org.cdpg.dx.common.HttpStatusCode;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.response.ResponseBuilder;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.common.validations.idhandler.GetIdFromBodyHandler;
import org.cdpg.dx.common.validations.idhandler.GetIdFromParams;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.rs.audit.util.DataplaneAuditHelper;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;
import org.cdpg.dx.rs.ngsild.util.Util;
import org.cdpg.dx.rs.rsp.gateway.util.GatewayParamValidator;
import org.cdpg.dx.rs.rsp.gateway.util.QueryMapper2;
import org.cdpg.dx.validations.idvalidation.IdValidation;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessApplicableFilterHandlerGateway;

public class GatewayController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(GatewayController.class);
  private final DataBrokerService dataBrokerService;
  private final GatewayParamValidator gatewayParamValidator;
  private final URNGenerator urnGenerator;
  private final GetIdFromParams getIdFromParams = new GetIdFromParams();
  private final GetIdFromBodyHandler getIdFromBodyHandler = new GetIdFromBodyHandler();
  private final AppIdItemAccessHandler appIdItemAccessHandler;
  private final ItemAccessApplicableFilterHandlerGateway itemAccessApplicableFilterHandlerGateway;
  private final AuditingHandler auditingHandler;
  private final IdValidation idValidation;

  /*private final RedisAccessLimitHandler redisAccessLimitHandler;*/

  public GatewayController(
      DataBrokerService dataBrokerService,
      GatewayParamValidator gatewayParamValidator,
      URNGenerator urnGenerator,
      String controlPlaneDomain,
      AuditingHandler auditingHandler,
      AppIdItemAccessHandler appIdItemAccessHandler /*,
      RedisService redisService,
      String redisKeyPrefix*/) {
    this.dataBrokerService = dataBrokerService;
    this.appIdItemAccessHandler = appIdItemAccessHandler;
    this.gatewayParamValidator = gatewayParamValidator;
    this.urnGenerator = urnGenerator;
    this.itemAccessApplicableFilterHandlerGateway =
        new ItemAccessApplicableFilterHandlerGateway(controlPlaneDomain);
    this.auditingHandler = auditingHandler;
    this.idValidation = new IdValidation();
    /*this.redisAccessLimitHandler = new RedisAccessLimitHandler(redisService, redisKeyPrefix);*/
  }

  @Override
  public void register(RouterBuilder builder) {
    builder
        .operation(GET_SPATIAL_SEARCH)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromParams)
        .handler(AuthorizationHandler.forScopes(Scopes.DATA_ACCESS))
        .handler(appIdItemAccessHandler)
        .handler(itemAccessApplicableFilterHandlerGateway)
        /*.handler(redisAccessLimitHandler)*/
        .handler(idValidation)
        .handler(ctx -> handleGet(ctx, false));
    builder
        .operation(GET_TEMPORAL_ENTITY_SEARCH)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromParams)
        .handler(AuthorizationHandler.forScopes(Scopes.DATA_ACCESS))
        .handler(appIdItemAccessHandler)
        .handler(itemAccessApplicableFilterHandlerGateway)
        /*.handler(redisAccessLimitHandler)*/
        .handler(idValidation)
        .handler(ctx -> handleGet(ctx, true));

    // POST endpoints
    builder
        .operation(POST_SPATIAL_COMPLEX_QUERY)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromBodyHandler)
        .handler(AuthorizationHandler.forScopes(Scopes.DATA_ACCESS))
        .handler(appIdItemAccessHandler)
        .handler(itemAccessApplicableFilterHandlerGateway)
        /*.handler(redisAccessLimitHandler)*/
        .handler(idValidation)
        .handler(ctx -> handlePost(ctx, false));
    builder
        .operation(POST_SPATIAL_TEMPORAL_COMPLEX_QUERY)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromBodyHandler)
        .handler(AuthorizationHandler.forScopes(Scopes.DATA_ACCESS))
        .handler(appIdItemAccessHandler)
        .handler(itemAccessApplicableFilterHandlerGateway)
        /*.handler(redisAccessLimitHandler)*/
        .handler(idValidation)
        .handler(ctx -> handlePost(ctx, true));
  }

  private void handlePost(RoutingContext ctx, boolean isTemporalApi) {
    JsonObject body = ctx.body().asJsonObject();
    LOGGER.debug("Handling POST {} with body: {}", ctx.request().path(), body.encodePrettily());
    String instanceId = ctx.request().getHeader(HEADER_HOST);
    String publicKey = ctx.request().getHeader(HEADER_PUBLIC_KEY);
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(ctx);
    /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    String headersAcceptType =
        gatewayParamValidator.validateAndSelectBestMediaType(ctx.request().getHeader("Accept"));
    LOGGER.warn("headersAcceptType : " + headersAcceptType);

    JsonObject bodyJson =
        ctx.body() != null && ctx.body().asJsonObject() != null
            ? ctx.body().asJsonObject()
            : new JsonObject();
    LOGGER.info("Info: request Json :: " + bodyJson);
    JsonObject requestJson = bodyJson.copy();
    MultiMap params = ctx.request().params(true);
    MultiMap requestConvertedParam = Util.convertBodyToParams(bodyJson);
    LOGGER.trace("Info: Converted Params :: " + requestConvertedParam);

    try {
      if (isTemporalApi) {
        gatewayParamValidator.validateQueryParamsTemporalEntities(requestConvertedParam);
        gatewayParamValidator.validateQueryParamsPost(params);
      } else {
        gatewayParamValidator.validateQueryParamsEntities(requestConvertedParam);
        gatewayParamValidator.validateQueryParamsPost(params);
      }
      if (body.containsKey(NGSILDQUERY_TEMPORALQ)) {
        JsonObject temporalQ = body.getJsonObject(NGSILDQUERY_TEMPORALQ);
        gatewayParamValidator.validateTemporal(
            temporalQ.getString(NGSILDQUERY_TIMEREL),
            temporalQ.getString(NGSILDQUERY_TIMEAT),
            temporalQ.getString(NGSILDQUERY_ENDTIMEAT),
            temporalQ.getString(NGSILDQUERY_TIMEPROPERTY),
            false,
            isTemporalApi);
      }
      if (body.containsKey(NGSILDQUERY_GEOQ)) {
        JsonObject geoQ = body.getJsonObject(NGSILDQUERY_GEOQ);
        gatewayParamValidator.validateGeometry(
            geoQ.getString(NGSILDQUERY_GEOMETRY),
            geoQ.getString(NGSILDQUERY_GEOREL),
            geoQ.getString(NGSILDQUERY_COORDINATES));
      }

      gatewayParamValidator.validateAggrs(
          requestConvertedParam.get(NGSILD_OPTIONS),
          requestConvertedParam.get(NGSILDQUERY_AGGR_METHODS));
      if (body.containsKey(NGSILDQUERY_Q)) {
        gatewayParamValidator.validateQ(body.getString(NGSILDQUERY_Q), isTemporalApi);
      }
      if (body.containsKey(NGSILDQUERY_PICK)) {
        gatewayParamValidator.validatePick(body.getString(NGSILDQUERY_PICK));
      }
      if (body.containsKey(NGSILDQUERY_OMIT)) {
        gatewayParamValidator.validateOmit(body.getString(NGSILDQUERY_OMIT));
      }
      if (body.containsKey(NGSILDQUERY_ATTRIBUTE)) {
        gatewayParamValidator.validateAttrs(body.getString(NGSILDQUERY_ATTRIBUTE));
      }
      gatewayParamValidator.validatePickAndAggrs(
          requestConvertedParam.get(NGSILDQUERY_PICK),
          requestConvertedParam.get(NGSILDQUERY_AGGR_METHODS));
      params.entries().forEach(e -> requestJson.put(e.getKey(), e.getValue()));
      LOGGER.debug("Gateway param validator completed");

    } catch (DxBadRequestException exception) {
      LOGGER.error("Bad request: {}", exception.getMessage());
      ctx.fail(exception);
      return;
    }

    HttpServerResponse response = ctx.response();
    NGSILDQueryParams gatewayQueryParams = new NGSILDQueryParams(requestJson);
    LOGGER.debug("Constructed gatewayQueryParams: {}", gatewayQueryParams);
    JsonObject jsonQuery = new QueryMapper2().toJson(gatewayQueryParams, isTemporalApi);
    jsonQuery.put(IUDX_INSTANCEID, instanceId);
    jsonQuery.put(HEADER_PUBLIC_KEY, publicKey);
    jsonQuery.put("api", ctx.normalizedPath());
    String searchType = jsonQuery.getString(IUDX_SEARCH_TYPE);
    gatewayParamValidator.isValidQueryWithFilters(searchType, applicableFilter);
    jsonQuery.put("applicableFilters", applicableFilter);
    LOGGER.debug("Constructed post json query for data broker RMQ: {}", jsonQuery.encodePrettily());
    dataBrokerService
        .executeAdapterQueryRPC(jsonQuery)
        .onSuccess(
            rpcResponse -> {
              // LOGGER.debug("response from adapter {}", rpcResponse.encodePrettily());
              int statusCode = rpcResponse.getInteger("statusCode", 200);

              if (statusCode >= 200 && statusCode < 300) {
                // success
                response
                    .putHeader("Content-Type", headersAcceptType)
                    .putHeader(HEADER_ALLOW_ORIGIN, "*")
                    .putHeader(
                        "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                    .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    /*.putHeader(
                        NGSILD_RESULTS_COUNT,
                        String.valueOf(rpcResponse.getJsonArray("results").size()))
                    .putHeader(NGSILD_LIMIT, String.valueOf(gatewayQueryParams.getPageSize()))
                    .putHeader(NGSILD_OFFSET, String.valueOf(gatewayQueryParams.getPageFrom()))*/
                    .setStatusCode(200)
                    .end(rpcResponse.getJsonArray("results").encodePrettily());
                long bytesWritten = response.bytesWritten();
                RoutingContextHelper.setResponseSize(ctx, bytesWritten);
                AuditLog auditLog =
                    DataplaneAuditHelper.createAuditingLogs(
                        ctx,
                        RoutingContextHelper.getId(ctx),
                        "POST",
                        GATEWAY,
                        DOWNLOAD,
                        bytesWritten);
                RoutingContextHelper.setAuditingLog(ctx, auditLog);
              } else {
                // remote service failure
                LOGGER.error("Failed RPC response: {}", rpcResponse.encodePrettily());
                HttpStatusCode status = HttpStatusCode.getByValue(statusCode);
                ResponseBuilder.sendError(ctx, status, urnGenerator);
                /*ctx.fail(statusCode);*/
              }
            })
        .onFailure(
            err -> {
              LOGGER.error("Data broker RPC failed", err);
              ctx.fail(err);
            });
  }

  private void handleGet(RoutingContext ctx, boolean isTemporalApi) {
    MultiMap params = ctx.request().params(true);
    String instanceId = ctx.request().getHeader(HEADER_HOST);
    String publicKey = ctx.request().getHeader(HEADER_PUBLIC_KEY);
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(ctx);
    /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    LOGGER.debug("Handling GET {} with query params: {}", ctx.request().path(), params);
    String headersAcceptType =
        gatewayParamValidator.validateAndSelectBestMediaType(ctx.request().getHeader("Accept"));
    LOGGER.warn("headersAcceptType :: " + headersAcceptType);
    try {

      if (isTemporalApi) {
        gatewayParamValidator.validateQueryParamsTemporalEntities(params);
      } else {
        gatewayParamValidator.validateQueryParamsEntities(params);
      }
      gatewayParamValidator.validateTemporal(
          params.get(NGSILDQUERY_TIMEREL),
          params.get(NGSILDQUERY_TIMEAT),
          params.get(NGSILDQUERY_ENDTIMEAT),
          params.get(NGSILDQUERY_TIMEPROPERTY),
          false,
          isTemporalApi);

      gatewayParamValidator.validateAggrs(
          params.get(NGSILD_OPTIONS), params.get(NGSILDQUERY_AGGR_METHODS));

      gatewayParamValidator.validateGeometry(
          params.get(NGSILDQUERY_GEOMETRY),
          params.get(NGSILDQUERY_GEOREL),
          params.get(NGSILDQUERY_COORDINATES));
      gatewayParamValidator.validateQ(params.get(NGSILDQUERY_Q), isTemporalApi);

      gatewayParamValidator.validatePick(params.get(NGSILDQUERY_PICK));
      gatewayParamValidator.validateOmit(params.get(NGSILDQUERY_OMIT));
      gatewayParamValidator.validateAttrs(params.get(ApiConstants.NGSILDQUERY_ATTRIBUTE));
      gatewayParamValidator.validatePickAndAggrs(
          params.get(NGSILDQUERY_PICK), params.get(NGSILDQUERY_AGGR_METHODS));
      LOGGER.debug("Gateway param validator completed");
    } catch (DxBadRequestException e) {
      LOGGER.error("Bad request: {}", e.getMessage());
      ctx.fail(e);
      return;
    }

    NGSILDQueryParams gatewayQueryParams = new NGSILDQueryParams(params);
    LOGGER.debug("Constructed gatewayQueryParams: {}", gatewayQueryParams);
    JsonObject jsonQuery = new QueryMapper2().toJson(gatewayQueryParams, isTemporalApi);
    jsonQuery.put(IUDX_INSTANCEID, instanceId);
    jsonQuery.put(HEADER_PUBLIC_KEY, publicKey);
    jsonQuery.put("api", ctx.normalizedPath());
    String searchType = jsonQuery.getString(IUDX_SEARCH_TYPE);
    gatewayParamValidator.isValidQueryWithFilters(searchType, applicableFilter);
    jsonQuery.put("applicableFilters", applicableFilter);
    LOGGER.debug("Constructed JSON query for data broker RMQ: {}", jsonQuery.encodePrettily());
    HttpServerResponse response = ctx.response();
    dataBrokerService
        .executeAdapterQueryRPC(jsonQuery)
        .onSuccess(
            rpcResponse -> {
              // LOGGER.debug("response from adapter {}", rpcResponse.encodePrettily());
              int statusCode = rpcResponse.getInteger("statusCode", 200);

              if (statusCode >= 200 && statusCode < 300) {
                // success
                response
                    .putHeader("Content-Type", headersAcceptType)
                    .putHeader(HEADER_ALLOW_ORIGIN, "*")
                    .putHeader(
                        "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                    .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    /*.putHeader(
                        NGSILD_RESULTS_COUNT,
                        String.valueOf(rpcResponse.getJsonArray("results").size()))
                    .putHeader(NGSILD_LIMIT, String.valueOf(gatewayQueryParams.getPageSize()))
                    .putHeader(NGSILD_OFFSET, String.valueOf(gatewayQueryParams.getPageFrom()))*/
                    .setStatusCode(200)
                    .end(rpcResponse.getJsonArray("results").encodePrettily());
                long bytesWritten = response.bytesWritten();
                RoutingContextHelper.setResponseSize(ctx, bytesWritten);
                AuditLog auditLog =
                    DataplaneAuditHelper.createAuditingLogs(
                        ctx, params.get(ID), "GET", GATEWAY, DOWNLOAD, bytesWritten);
                RoutingContextHelper.setAuditingLog(ctx, auditLog);
              } else {
                // remote service failure
                LOGGER.error("Fail RPC response: {}", rpcResponse.encodePrettily());
                HttpStatusCode status = HttpStatusCode.getByValue(statusCode);
                ResponseBuilder.sendError(ctx, status, urnGenerator);
                /*ctx.fail(statusCode);*/
              }
            })
        .onFailure(
            err -> {
              LOGGER.error("Data broker RPC failed", err);
              ctx.fail(err);
            });
  }
}
