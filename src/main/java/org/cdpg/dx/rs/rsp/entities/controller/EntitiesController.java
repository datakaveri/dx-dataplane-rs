package org.cdpg.dx.rs.rsp.entities.controller;

import static org.cdpg.dx.rs.rsp.entities.controller.config.*;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.apiserver.ProxyApiServerVerticle;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.rs.query.NGSILDQueryParams;
import org.cdpg.dx.rs.query.QueryMapper;
import org.cdpg.dx.rs.query.QueryRequest;
import org.cdpg.dx.rs.query.Util;
import org.cdpg.dx.rs.validation.ParamsValidator;

public class EntitiesController implements ApiController {

  private static final Logger LOGGER = LogManager.getLogger(ProxyApiServerVerticle.class);

  private final ParamsValidator validator = new ParamsValidator(30, 90); // example limits

  @Override
  public void register(RouterBuilder builder) {
    // GET endpoints
    builder.operation(GET_SPATIAL_SEARCH).handler(ctx -> handleGet(ctx, false));
    builder.operation(GET_TEMPORAL_ENTITY_SEARCH).handler(ctx -> handleGet(ctx, true));

    // POST endpoints
    builder.operation(POST_SPATIAL_COMPLEX_QUERY).handler(ctx -> handlePost(ctx, false));
    builder.operation(POST_SPATIAL_TEMPORAL_COMPLEX_QUERY).handler(ctx -> handlePost(ctx, true));

    // Async endpoints
    builder.operation(GET_ASYNC_SEARCH).handler(this::handleGetAsyncSearch);
    builder.operation(GET_ASYNC_SEARCH_STATUS).handler(this::handleGetAsyncSearchStatus);
  }

  private void handleGet(RoutingContext ctx, boolean isTemporalApi) {
    MultiMap params = ctx.request().params(true);
    LOGGER.debug("Handling GET {} with query params: {}", ctx.request().path(), params);

    try {
      // Extract temporal params
      String timeRel = params.get("timerel");
      String time = params.get("time");
      String endTime = params.get("endtime");
      String timeProperty = params.get("timeproperty");

      // Validate temporal fields according to API type
      validator.validateTemporal(timeRel, time, endTime, timeProperty, false, isTemporalApi);

      // Validate geo params if present
      validator.validateGeometry(params.get("geometry"), params.get("coordinates"));
      validator.validateDistance(params.get("georel"));

    } catch (DxBadRequestException e) {
      ctx.fail(e);
      return;
    }

    QueryRequest queryRequest = Util.queryRequestFromParams(params);
    NGSILDQueryParams ngsildQuery = new NGSILDQueryParams(queryRequest);

    QueryMapper queryMapper = new QueryMapper();
    JsonObject json = queryMapper.toJson(ngsildQuery, isTemporalApi);

    ctx.response()
        .putHeader("content-type", "application/json")
        .end(new JsonObject().put("queryJson", json).put("path", ctx.request().path()).encode());
  }

  private void handlePost(RoutingContext ctx, boolean isTemporalApi) {
    JsonObject body = ctx.body().asJsonObject();
    LOGGER.debug("Handling POST {} with body: {}", ctx.request().path(), body.encodePrettily());

    try {
      // Extract temporal fields from body if present
      String timeRel = body.getString("timerel");
      String time = body.getString("time");
      String endTime = body.getString("endtime");
      String timeProperty = body.getString("timeproperty");

      validator.validateTemporal(timeRel, time, endTime, timeProperty, false, isTemporalApi);

      validator.validateGeometry(body.getString("geometry"), body.getString("coordinates"));
      validator.validateDistance(body.getString("georel"));

    } catch (DxBadRequestException e) {
      ctx.fail(e);
      return;
    }

    QueryRequest queryRequest = Util.queryRequestFromBody(body);
    NGSILDQueryParams ngsildQuery = new NGSILDQueryParams(queryRequest);

    QueryMapper queryMapper = new QueryMapper();
    JsonObject json = queryMapper.toJson(ngsildQuery, isTemporalApi);

    ctx.response()
        .putHeader("content-type", "application/json")
        .end(new JsonObject().put("queryJson", json).put("path", ctx.request().path()).encode());
  }

  private void handleGetAsyncSearch(RoutingContext ctx) {
    JsonObject response =
        new JsonObject()
            .put("queryParams", ctx.queryParams().entries())
            .put("path", ctx.request().path());
    ctx.response().putHeader("content-type", "application/json").end(response.encode());
  }

  private void handleGetAsyncSearchStatus(RoutingContext ctx) {
    JsonObject response =
        new JsonObject()
            .put("queryParams", ctx.queryParams().entries())
            .put("path", ctx.request().path());
    ctx.response().putHeader("content-type", "application/json").end(response.encode());
  }
}
