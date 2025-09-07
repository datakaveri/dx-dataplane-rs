package org.cdpg.dx.rs.rsp.entities.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;
import static org.cdpg.dx.rs.rsp.entities.controller.config.*;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.response.ResponseBuilder;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.rs.query.NGSILDQueryParams;
import org.cdpg.dx.rs.query.QueryMapper;
import org.cdpg.dx.rs.query.QueryRequest;
import org.cdpg.dx.rs.query.Util;
import org.cdpg.dx.rs.validation.ParamsValidator;
import org.cdpg.dx.validations.filter.ApplicableFilter;
import org.cdpg.dx.validations.idhandler.GetIdFromParams;

public class EntitiesController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(EntitiesController.class);

  private final DataBrokerService dataBrokerService;
  private final ParamsValidator paramsValidator;
  private final URNGenerator urnGenerator;
  private final ApplicableFilter applicableFilterHandler;
  private final GetIdFromParams getIdFromParams = new GetIdFromParams();

  public EntitiesController(
      DataBrokerService dataBrokerService,
      ParamsValidator paramsValidator,
      URNGenerator urnGenerator) {
    this.dataBrokerService = dataBrokerService;
    this.paramsValidator = paramsValidator;
    this.urnGenerator = urnGenerator;
    this.applicableFilterHandler = new ApplicableFilter("https://controlplane.tgdex.iudx.io");
  }

  @Override
  public void register(RouterBuilder builder) {
    // GET endpoints
    builder
        .operation(GET_SPATIAL_SEARCH)
        .handler(getIdFromParams)
        .handler(applicableFilterHandler)
        .handler(ctx -> handleGet(ctx, false));
    builder.operation(GET_TEMPORAL_ENTITY_SEARCH).handler(ctx -> handleGet(ctx, true));

    // POST endpoints
    builder.operation(POST_SPATIAL_COMPLEX_QUERY).handler(ctx -> handlePost(ctx, false));
    builder.operation(POST_SPATIAL_TEMPORAL_COMPLEX_QUERY).handler(ctx -> handlePost(ctx, true));
  }

  private void handleGet(RoutingContext ctx, boolean isTemporalApi) {
    MultiMap params = ctx.request().params(true);
    String instanceId = ctx.request().getHeader(HEADER_HOST);
    String publicKey = ctx.request().getHeader(HEADER_PUBLIC_KEY);
    LOGGER.debug("Handling GET {} with query params: {}", ctx.request().path(), params);

    try {

      paramsValidator.validateQueryParams(params);

      // Validate temporal fields
      paramsValidator.validateTemporal(
          params.get("timerel"),
          params.get("time"),
          params.get("endtime"),
          params.get("timeproperty"),
          false,
          isTemporalApi);

      // Validate geo fields
      paramsValidator.validateGeometry(
          params.get("geometry"), params.get("georel"), params.get("coordinates"));

      // Validate Q-type attributes if present
      paramsValidator.validateQ(params.get("q"));
      paramsValidator.validateAttrs(params.get("attrs"));

    } catch (DxBadRequestException e) {
      ctx.fail(e);
      return;
    }

    QueryRequest queryRequest = Util.queryRequestFromParams(params);
    NGSILDQueryParams ngsildQuery = new NGSILDQueryParams(queryRequest);
    JsonObject jsonQuery = new QueryMapper().toJson(ngsildQuery, isTemporalApi);
    jsonQuery.put(JSON_INSTANCEID, instanceId);
    jsonQuery.put(HEADER_PUBLIC_KEY, publicKey);

    LOGGER.debug("Constructed JSON query for data broker RMQ: {}", jsonQuery.encodePrettily());
    dataBrokerService
        .executeAdapterQueryRPC(jsonQuery)
        .onSuccess(
            rpcResponse -> {
              LOGGER.debug("Data broker RPC response: {}", rpcResponse.encode());
              ResponseBuilder.sendSuccess(ctx, rpcResponse, null, urnGenerator);
            })
        .onFailure(
            err -> {
              LOGGER.error("Data broker query failed: {}", err.getClass(), err);
              ctx.fail(err);
            });
  }

  private void handlePost(RoutingContext ctx, boolean isTemporalApi) {
    JsonObject body = ctx.body().asJsonObject();
    LOGGER.debug("Handling POST {} with body: {}", ctx.request().path(), body.encodePrettily());

    try {

      paramsValidator.validateBodyParams(body);

      // Temporal validation
      if (body.containsKey("temporalQ")) {
        JsonObject temporalQ = body.getJsonObject("temporalQ");
        paramsValidator.validateTemporal(
            temporalQ.getString("timerel"),
            temporalQ.getString("time"),
            temporalQ.getString("endtime"),
            temporalQ.getString("timeproperty"),
            false,
            isTemporalApi);
      }

      // Geo validation
      if (body.containsKey("geoQ")) {
        JsonObject geoQ = body.getJsonObject("geoQ");
        paramsValidator.validateGeometry(
            geoQ.getString("geometry"), geoQ.getString("georel"), geoQ.getString("coordinates"));
      }

      // Q-type validation
      if (body.containsKey("q")) {
        paramsValidator.validateQ(body.getString("q"));
      }
      // Attrs validation
      if (body.containsKey("attrs")) {
        paramsValidator.validateAttrs(body.getString("attrs"));
      }

    } catch (DxBadRequestException e) {
      ctx.fail(e);
      return;
    }

    QueryRequest queryRequest = Util.queryRequestFromBody(body);
    NGSILDQueryParams ngsildQuery = new NGSILDQueryParams(queryRequest);
    JsonObject JsonQuery = new QueryMapper().toJson(ngsildQuery, isTemporalApi);

    dataBrokerService
        .executeAdapterQueryRPC(JsonQuery)
        .onSuccess(
            rpcResponse -> {
              LOGGER.debug("Data broker RPC response: {}", rpcResponse.encode());
              ResponseBuilder.sendSuccess(ctx, rpcResponse, null, urnGenerator);
            })
        .onFailure(
            err -> {
              ctx.fail(err);
            });
  }
}
