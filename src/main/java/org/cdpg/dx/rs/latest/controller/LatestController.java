package org.cdpg.dx.rs.latest.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;
import static org.cdpg.dx.essearch.util.Constants.PAGE_KEY;
import static org.cdpg.dx.essearch.util.Constants.SIZE_KEY;
import static org.cdpg.dx.rs.audit.util.Constants.NGSILD;
import static org.cdpg.dx.rs.latest.util.Constants.ID;

import io.vertx.core.MultiMap;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auditing.model.AuditLog;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.request.PostSearchRequestBuilder;
import org.cdpg.dx.common.response.ResponseBuilder;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.rs.audit.util.DataplaneAuditHelper;
import org.cdpg.dx.rs.latest.model.GetRequestModel;
import org.cdpg.dx.rs.latest.service.LatestService;
import org.cdpg.dx.validations.idhandler.GetIdFromPathHandler;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessApplicableFilterHandler;

/** Controller to handle latest entity data retrieval endpoints. */
public class LatestController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(LatestController.class);
  private final LatestService latestService;
  private final GetIdFromPathHandler getIdFromPathHandler = new GetIdFromPathHandler();
  private final ItemAccessApplicableFilterHandler itemAccessApplicableFilterHandler;
  private final URNGenerator urnGenerator;
  private final AuditingHandler auditingHandler;

  /** Initializes the latest controller with required services and config. */
  public LatestController(
      LatestService latestService,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      AuditingHandler auditingHandler) {
    this.latestService = latestService;
    this.itemAccessApplicableFilterHandler =
        new ItemAccessApplicableFilterHandler(controlPlaneDomain);
    this.urnGenerator = urnGenerator;
    this.auditingHandler = auditingHandler;
  }

  @Override
  public void register(RouterBuilder builder) {
    builder
        .operation(POST_LATEST_ENTITY_DATA_SEARCH)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(itemAccessApplicableFilterHandler)
        .handler(this::handlePostEntityDataSearch);
    builder
        .operation(GET_LATEST_ENTITY_DATA)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(itemAccessApplicableFilterHandler)
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
                AuditLog auditLog =
                    DataplaneAuditHelper.createAuditingLogs(
                        RoutingContextHelper.getItemMetaData(routingContext),
                        id,
                        RoutingContextHelper.getRequestPath(routingContext),
                        "POST",
                        routingContext.user().subject(),
                        NGSILD,
                        "consumer");
                RoutingContextHelper.setAuditingLog(routingContext, auditLog);
                ResponseBuilder.sendSuccess(
                    routingContext,
                    searchService.getElasticsearchResponses(),
                    searchService.getPaginationInfo(),
                    urnGenerator);
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
    String sort = ctx.queryParams().get("sort");
    String sortBy;
    if (sort == null) {
      sortBy = "observationDateTime:desc";
    } else {
      sortBy = sort;
    }
    String sortOrder = sortBy.split(":")[1];
    sortBy = sortBy.split(":")[0];
    GetRequestModel getRequestModel =
        new GetRequestModel(id, size, page, time, endTime, timeRel, sortBy, sortOrder);
    latestService
        .getSearch(getRequestModel)
        .onSuccess(
            result -> {
              AuditLog auditLog =
                  DataplaneAuditHelper.createAuditingLogs(
                      RoutingContextHelper.getItemMetaData(ctx),
                      id,
                      RoutingContextHelper.getRequestPath(ctx),
                      "GET",
                      ctx.user().subject(),
                      NGSILD,
                      "consumer");
              RoutingContextHelper.setAuditingLog(ctx, auditLog);
              sendResponse(ctx, result);
            })
        .onFailure(
            err -> {
              LOGGER.error("Error processing latest data request for ID: {}", id, err);
              ctx.fail(err);
            });
  }

  private void sendResponse(RoutingContext ctx, ResponseModel responseModel) {
    ResponseBuilder.sendSuccess(
        ctx,
        responseModel.getElasticsearchResponses(),
        responseModel.getPaginationInfo(),
        urnGenerator);
  }

  public int getSize(MultiMap params) {
    return params.get(SIZE_KEY) != null ? Integer.parseInt(params.get(SIZE_KEY)) : 10;
  }

  public int getPage(MultiMap params) {
    return params.get(PAGE_KEY) != null ? Integer.parseInt(params.get(PAGE_KEY)) : 1;
  }
}
