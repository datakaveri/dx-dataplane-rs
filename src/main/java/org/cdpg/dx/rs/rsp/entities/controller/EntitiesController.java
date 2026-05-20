package org.cdpg.dx.rs.rsp.entities.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;
import static org.cdpg.dx.apiserver.config.ApiConstants.HEADER_PUBLIC_KEY;
import static org.cdpg.dx.rs.audit.util.Constants.*;
import static org.cdpg.dx.rs.rsp.entities.controller.config.*;

import io.vertx.core.MultiMap;
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
import org.cdpg.dx.auth.model.Scopes;
import org.cdpg.dx.common.HttpStatusCode;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.response.ResponseBuilder;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.database.redis.service.RedisService;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.rs.audit.util.DataplaneAuditHelper;
import org.cdpg.dx.rs.query.NGSILDQueryParams;
import org.cdpg.dx.rs.query.QueryMapper;
import org.cdpg.dx.rs.query.QueryRequest;
import org.cdpg.dx.rs.query.Util;
import org.cdpg.dx.rs.validation.ParamsValidator;
import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.common.validations.idhandler.GetIdFromBodyHandler;
import org.cdpg.dx.common.validations.idhandler.GetIdFromParams;
import org.cdpg.dx.validations.idvalidation.IdValidation;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessApplicableFilterHandlerGateway;
import org.cdpg.dx.validations.ratelimit.RedisAccessLimitHandler;

public class EntitiesController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(EntitiesController.class);

  private final DataBrokerService dataBrokerService;
  private final ParamsValidator paramsValidator;
  private final URNGenerator urnGenerator;
  private final GetIdFromParams getIdFromParams = new GetIdFromParams();
  private final GetIdFromBodyHandler getIdFromBodyHandler = new GetIdFromBodyHandler();
  private final AppIdItemAccessHandler appIdItemAccessHandler;
  private final ItemAccessApplicableFilterHandlerGateway itemAccessApplicableFilterHandlerGateway;
  private final IdValidation idValidation;
  private final AuditingHandler auditingHandler;
  /*private final RedisAccessLimitHandler redisAccessLimitHandler;*/

  public EntitiesController(
      DataBrokerService dataBrokerService,
      ParamsValidator paramsValidator,
      URNGenerator urnGenerator,
      String controlPlaneDomain,
      AuditingHandler auditingHandler,
      AppIdItemAccessHandler appIdItemAccessHandler /*,
      RedisService redisService,
      String redisKeyPrefix*/) {
    this.dataBrokerService = dataBrokerService;
    this.appIdItemAccessHandler = appIdItemAccessHandler;
    this.paramsValidator = paramsValidator;
    this.urnGenerator = urnGenerator;
    this.itemAccessApplicableFilterHandlerGateway =
        new ItemAccessApplicableFilterHandlerGateway(controlPlaneDomain);
    this.idValidation = new IdValidation();
    this.auditingHandler = auditingHandler;
    /*this.redisAccessLimitHandler = new RedisAccessLimitHandler(redisService, redisKeyPrefix);*/
  }

  @Override
  public void register(RouterBuilder builder) {
    // GET endpoints
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

  private void handleGet(RoutingContext ctx, boolean isTemporalApi) {
    MultiMap params = ctx.request().params(true);
    String instanceId = ctx.request().getHeader(HEADER_HOST);
    String publicKey = ctx.request().getHeader(HEADER_PUBLIC_KEY);
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(ctx);
    LOGGER.debug("Handling GET {} with query params: {}", ctx.request().path(), params);

    try {
      paramsValidator.validateQueryParams(params);

      // Validate temporal fields
      paramsValidator.validateTemporal(
          params.get(NGSILDQUERY_TIMEREL),
          params.get(NGSILDQUERY_TIMEAT),
          params.get(NGSILDQUERY_ENDTIMEAT),
          params.get(NGSILDQUERY_TIMEPROPERTY),
          false,
          isTemporalApi);

      // Validate geo fields
      paramsValidator.validateGeometry(
          params.get(NGSILDQUERY_GEOMETRY),
          params.get(NGSILDQUERY_GEOREL),
          params.get(NGSILDQUERY_COORDINATES));

      // Validate Q-type attributes if present
      paramsValidator.validateQ(params.get(NGSILDQUERY_Q));
      paramsValidator.validateAttrs(params.get(NGSILDQUERY_ATTRIBUTE));

    } catch (DxBadRequestException e) {
      ctx.fail(e);
      return;
    }

    QueryRequest queryRequest = Util.queryRequestFromParams(params);
    NGSILDQueryParams ngsildQuery = new NGSILDQueryParams(queryRequest);
    LOGGER.debug("Constructed NGSILDQueryParams: {}", ngsildQuery);
    JsonObject jsonQuery = new QueryMapper().toJson(ngsildQuery, isTemporalApi);
    jsonQuery.put(IUDX_INSTANCEID, instanceId);
    jsonQuery.put(HEADER_PUBLIC_KEY, publicKey);
    jsonQuery.put("api", ctx.normalizedPath());
    String searchType = jsonQuery.getString(IUDX_SEARCH_TYPE);
    paramsValidator.isValidQueryWithFilters(searchType, applicableFilter);
    jsonQuery.put("applicableFilters", applicableFilter);
    LOGGER.debug("Constructed JSON query for data broker RMQ: {}", jsonQuery.encodePrettily());
    dataBrokerService
        .executeAdapterQueryRPC(jsonQuery)
        .onSuccess(
            rpcResponse -> {
              // LOGGER.debug("response from adapter {}", rpcResponse.encodePrettily());
              int statusCode = rpcResponse.getInteger("statusCode", 200);

              if (statusCode >= 200 && statusCode < 300) {
                // success
                ResponseBuilder.sendSuccess(ctx, rpcResponse.getJsonArray("results"), urnGenerator);
                long bytesWritten = ctx.response().bytesWritten();
                RoutingContextHelper.setResponseSize(ctx, bytesWritten);
                AuditLog auditLog =
                    DataplaneAuditHelper.createAuditingLogs(
                        ctx, params.get(ID), "GET", GATEWAY, DOWNLOAD, bytesWritten);
                RoutingContextHelper.setAuditingLog(ctx, auditLog);
              } else {
                // remote service failure
                LOGGER.error("Received RPC response: {}", rpcResponse.encodePrettily());
                HttpStatusCode status = HttpStatusCode.getByValue(statusCode);
                ResponseBuilder.sendError(ctx, status, urnGenerator);
              }
            })
        .onFailure(
            err -> {
              LOGGER.error("Data broker RPC failed", err);
              ctx.fail(err);
            });
  }

  private void handlePost(RoutingContext ctx, boolean isTemporalApi) {
    JsonObject body = ctx.body().asJsonObject();
    LOGGER.debug("Handling POST {} with body: {}", ctx.request().path(), body.encodePrettily());
    String instanceId = ctx.request().getHeader(HEADER_HOST);
    String publicKey = ctx.request().getHeader(HEADER_PUBLIC_KEY);
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(ctx);
    try {
      paramsValidator.validateBodyParams(body);

      // Temporal validation
      if (body.containsKey(NGSILDQUERY_TEMPORALQ)) {
        JsonObject temporalQ = body.getJsonObject(NGSILDQUERY_TEMPORALQ);
        paramsValidator.validateTemporal(
            temporalQ.getString(NGSILDQUERY_TIMEREL),
            temporalQ.getString(NGSILDQUERY_TIMEAT),
            temporalQ.getString(NGSILDQUERY_ENDTIMEAT),
            temporalQ.getString(NGSILDQUERY_TIMEPROPERTY),
            false,
            isTemporalApi);
      }

      // Geo validation
      if (body.containsKey(NGSILDQUERY_GEOQ)) {
        JsonObject geoQ = body.getJsonObject(NGSILDQUERY_GEOQ);
        paramsValidator.validateGeometry(
            geoQ.getString(NGSILDQUERY_GEOMETRY),
            geoQ.getString(NGSILDQUERY_GEOREL),
            geoQ.getString(NGSILDQUERY_COORDINATES));
      }

      // Q-type validation
      if (body.containsKey(NGSILDQUERY_Q)) {
        paramsValidator.validateQ(body.getString(NGSILDQUERY_Q));
      }
      // Attrs validation
      if (body.containsKey(NGSILDQUERY_ATTRIBUTE)) {
        paramsValidator.validateAttrs(body.getString(NGSILDQUERY_ATTRIBUTE));
      }

    } catch (DxBadRequestException e) {
      ctx.fail(e);
      return;
    }

    QueryRequest queryRequest = Util.queryRequestFromBody(body);
    NGSILDQueryParams ngsildQuery = new NGSILDQueryParams(queryRequest);
    JsonObject jsonQuery = new QueryMapper().toJson(ngsildQuery, isTemporalApi);
    jsonQuery.put(IUDX_INSTANCEID, instanceId);
    jsonQuery.put(HEADER_PUBLIC_KEY, publicKey);
    jsonQuery.put("api", ctx.normalizedPath());
    String searchType = jsonQuery.getString(IUDX_SEARCH_TYPE);
    paramsValidator.isValidQueryWithFilters(searchType, applicableFilter);
    jsonQuery.put("applicableFilters", applicableFilter);
    LOGGER.debug("Constructed JSON query for data broker RMQ: {}", jsonQuery.encodePrettily());
    dataBrokerService
        .executeAdapterQueryRPC(jsonQuery)
        .onSuccess(
            rpcResponse -> {
              int statusCode = rpcResponse.getInteger("statusCode", 200);

              if (statusCode >= 200 && statusCode < 300) {
                // success
                ResponseBuilder.sendSuccess(ctx, rpcResponse.getJsonArray("results"), urnGenerator);
                long bytesWritten = ctx.response().bytesWritten();
                RoutingContextHelper.setResponseSize(ctx, bytesWritten);
                AuditLog auditLog =
                    DataplaneAuditHelper.createAuditingLogs(
                        ctx, RoutingContextHelper.getId(ctx), "POST", GATEWAY, DOWNLOAD, bytesWritten);
                RoutingContextHelper.setAuditingLog(ctx, auditLog);
              } else {
                LOGGER.error("Received RPC response: {}", rpcResponse.encodePrettily());
                // remote service failure
                HttpStatusCode status = HttpStatusCode.getByValue(statusCode);
                ResponseBuilder.sendError(ctx, status, urnGenerator);
              }
            })
        .onFailure(
            err -> {
              LOGGER.error("Data broker RPC failed", err);
              ctx.fail(err);
            });
  }
}
