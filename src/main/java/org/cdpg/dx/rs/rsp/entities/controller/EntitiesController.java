package org.cdpg.dx.rs.rsp.entities.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;
import static org.cdpg.dx.apiserver.config.ApiConstants.HEADER_PUBLIC_KEY;
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
import org.cdpg.dx.common.HttpStatusCode;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.response.ResponseBuilder;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.rs.query.NGSILDQueryParams;
import org.cdpg.dx.rs.query.QueryMapper;
import org.cdpg.dx.rs.query.QueryRequest;
import org.cdpg.dx.rs.query.Util;
import org.cdpg.dx.rs.util.CheckItemAccessHandler;
import org.cdpg.dx.rs.validation.ParamsValidator;
import org.cdpg.dx.validations.filter.ApplicableFilter;
import org.cdpg.dx.validations.idhandler.GetIdFromBodyHandler;
import org.cdpg.dx.validations.idhandler.GetIdFromParams;

public class EntitiesController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(EntitiesController.class);

  private final DataBrokerService dataBrokerService;
  private final ParamsValidator paramsValidator;
  private final URNGenerator urnGenerator;
  private final ApplicableFilter applicableFilterHandler;
  private final GetIdFromParams getIdFromParams = new GetIdFromParams();
  private final GetIdFromBodyHandler getIdFromBodyHandler = new GetIdFromBodyHandler();
  private final CheckItemAccessHandler checkItemAccessHandler;
  private final AuditingHandler auditingHandler;

  public EntitiesController(
      DataBrokerService dataBrokerService,
      ParamsValidator paramsValidator,
      URNGenerator urnGenerator,
      String controlPlaneDomain,
      AuditingHandler auditingHandler) {
    this.dataBrokerService = dataBrokerService;
    this.paramsValidator = paramsValidator;
    this.urnGenerator = urnGenerator;
    this.applicableFilterHandler = new ApplicableFilter(controlPlaneDomain);
    this.checkItemAccessHandler = new CheckItemAccessHandler(controlPlaneDomain);
    this.auditingHandler = auditingHandler;
  }

  @Override
  public void register(RouterBuilder builder) {
    // GET endpoints
    builder
        .operation(GET_SPATIAL_SEARCH)
        .handler(getIdFromParams)
        .handler(checkItemAccessHandler)
        .handler(applicableFilterHandler)
        .handler(ctx -> handleGet(ctx, false));
    builder
        .operation(GET_TEMPORAL_ENTITY_SEARCH)
        .handler(getIdFromParams)
        .handler(checkItemAccessHandler)
        .handler(applicableFilterHandler)
        .handler(ctx -> handleGet(ctx, true));

    // POST endpoints
    builder
        .operation(POST_SPATIAL_COMPLEX_QUERY)
        .handler(getIdFromBodyHandler)
        .handler(checkItemAccessHandler)
        .handler(applicableFilterHandler)
        .handler(ctx -> handlePost(ctx, false));
    builder
        .operation(POST_SPATIAL_TEMPORAL_COMPLEX_QUERY)
        .handler(getIdFromBodyHandler)
        .handler(checkItemAccessHandler)
        .handler(applicableFilterHandler)
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
              int statusCode = rpcResponse.getInteger("statusCode", 200);

              if (statusCode >= 200 && statusCode < 300) {
                // success
                ResponseBuilder.sendSuccess(ctx, rpcResponse, urnGenerator);
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
                ResponseBuilder.sendSuccess(ctx, rpcResponse, urnGenerator);
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
