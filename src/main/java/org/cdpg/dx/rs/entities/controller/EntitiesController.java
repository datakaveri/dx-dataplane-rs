package org.cdpg.dx.rs.entities.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.POST_SPATIAL_SEARCH;
import static org.cdpg.dx.rs.entities.controller.config.*;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.apiserver.ProxyApiServerVerticle;

public class EntitiesController implements ApiController {
  Logger LOGGER = LogManager.getLogger(ProxyApiServerVerticle.class);

  @Override
  public void register(RouterBuilder builder) {
    builder.operation(GET_SPATIAL_SEARCH).handler(this::handleGetSpatialSearch);

    builder.operation(GET_TEMPORAL_ENTITY_SEARCH).handler(this::handleGetTemporalEntitySearch);

    builder.operation(POST_SPATIAL_COMPLEX_QUERY).handler(this::handlePostSpatialSearch);
    builder
        .operation(POST_SPATIAL_TEMPORAL_COMPLEX_QUERY)
        .handler(this::handlePostSpatialComplexQuery);
    builder.operation(GET_ASYNC_SEARCH).handler(this::handleGetAsyncSearch);
    builder.operation(GET_ASYNC_SEARCH_STATUS).handler(this::handleGetAsyncSearchStatus);
  }

  private void handleGetSpatialSearch(RoutingContext ctx) {
    JsonObject response =
        new JsonObject()
            .put("queryParams", ctx.queryParams().entries())
            .put("path", ctx.request().path());
    ctx.response().putHeader("content-type", "application/json").end(response.encode());
  }

  private void handleGetTemporalEntitySearch(RoutingContext ctx) {
    JsonObject response =
        new JsonObject()
            .put("queryParams", ctx.queryParams().entries())
            .put("path", ctx.request().path());
    ctx.response().putHeader("content-type", "application/json").end(response.encode());
  }

  private void handlePostSpatialSearch(RoutingContext ctx) {
    ctx.request()
        .bodyHandler(
            buffer -> {
              JsonObject requestBody = buffer.toJsonObject();
              ctx.response()
                  .putHeader("content-type", "application/json")
                  .end(requestBody.encode());
            });
  }

  private void handlePostSpatialComplexQuery(RoutingContext ctx) {
    ctx.request()
        .bodyHandler(
            buffer -> {
              JsonObject requestBody = buffer.toJsonObject();
              ctx.response()
                  .putHeader("content-type", "application/json")
                  .end(requestBody.encode());
            });
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
