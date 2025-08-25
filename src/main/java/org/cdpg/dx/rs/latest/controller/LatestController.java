package org.cdpg.dx.rs.latest.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;
import static org.cdpg.dx.essearch.util.Constants.PAGE_KEY;
import static org.cdpg.dx.essearch.util.Constants.SIZE_KEY;
import static org.cdpg.dx.rs.latest.util.Constants.ID;

import io.vertx.core.MultiMap;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.common.request.PostSearchRequestBuilder;
import org.cdpg.dx.common.response.ResponseBuilder;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.rs.latest.model.GetRequestModel;
import org.cdpg.dx.rs.latest.service.LatestService;
import org.cdpg.dx.rs.util.CheckItemAccessHandler;
import org.cdpg.dx.validations.idhandler.GetIdFromPathHandler;

/** Controller to handle latest entity data retrieval endpoints. */
public class LatestController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(LatestController.class);
  private final LatestService latestService;
  private final GetIdFromPathHandler getIdFromPathHandler = new GetIdFromPathHandler();
  private final CheckItemAccessHandler checkItemAccessHandler;
  /** Initializes the latest controller with required services and config. */
  public LatestController(LatestService latestService,String controlPlaneDomain) {
    this.latestService = latestService;
    this.checkItemAccessHandler=new CheckItemAccessHandler(controlPlaneDomain);
  }

  @Override
  public void register(RouterBuilder builder) {
    builder
        .operation(POST_LATEST_ENTITY_DATA_SEARCH)
        .handler(getIdFromPathHandler)
        .handler(checkItemAccessHandler)
        .handler(this::handlePostEntityDataSearch);
    builder
        .operation(GET_LATEST_ENTITY_DATA)
        .handler(getIdFromPathHandler)
        .handler(checkItemAccessHandler)
        .handler(this::handleGetSearchQuery);

    LOGGER.debug("Latest Controller deployed and route registered.");
  }

  private void handlePostEntityDataSearch(RoutingContext routingContext) {
    LOGGER.debug("Into handlePostEntityDataSearch()");
    String id = routingContext.pathParam(ID);
    try {
      SearchQuery searchQuery =
          PostSearchRequestBuilder.fromRoutingContext(routingContext)
              .setAssetSearch(false)
              .setCountApi(false)
              .build();
      latestService
          .postSearch(searchQuery, id)
          .onSuccess(
              searchService -> {
                ResponseBuilder.sendSuccess(
                    routingContext,
                    searchService.getElasticsearchResponses(),
                    searchService.getPaginationInfo());
              })
          .onFailure(
              err -> {
                LOGGER.error("Search request failed: {}", err.getMessage(), err);
                routingContext.fail(err);
              });

    } catch (Exception e) {
      LOGGER.error("Error processing search request: {}", e.getMessage(), e);
    }
  }

  private void handleGetSearchQuery(RoutingContext ctx) {
    LOGGER.debug("Handling latest data query");
    String id = ctx.pathParam(ID);
    MultiMap params = ctx.queryParams();
    int size = getSize(params);
    int page = getPage(params);
    String time = ctx.queryParams().get("time");
    String endTime = ctx.queryParams().get("endTime");
    String timeRel = ctx.queryParams().get("timeRel");
    GetRequestModel getRequestModel = new GetRequestModel(id, size, page, time, endTime, timeRel);
    latestService
        .getSearch(getRequestModel)
        .onSuccess(result -> sendResponse(ctx, result))
        .onFailure(
            err -> {
              LOGGER.error("Error processing latest data request for ID: {}", id, err);
              ctx.fail(err);
            });
  }

  private void sendResponse(RoutingContext ctx, ResponseModel responseModel) {
    ResponseBuilder.sendSuccess(
        ctx, responseModel.getElasticsearchResponses(), responseModel.getPaginationInfo());
  }

  public int getSize(MultiMap params) {
    return params.get(SIZE_KEY) != null ? Integer.parseInt(params.get(SIZE_KEY)) : 10;
  }

  public int getPage(MultiMap params) {
    return params.get(PAGE_KEY) != null ? Integer.parseInt(params.get(PAGE_KEY)) : 1;
  }
}
