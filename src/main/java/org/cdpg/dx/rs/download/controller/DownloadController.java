package org.cdpg.dx.rs.download.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.DOWNLOAD_ID_ENTITY_DATA;
import static org.cdpg.dx.apiserver.config.ApiConstants.DOWNLOAD_PUT_SEARCH_DATA;
import static org.cdpg.dx.essearch.util.Constants.PAGE_KEY;
import static org.cdpg.dx.essearch.util.Constants.SIZE_KEY;
import static org.cdpg.dx.rs.audit.util.Constants.NGSILD;
import static org.cdpg.dx.rs.audit.util.Constants.VIEW;
import static org.cdpg.dx.rs.download.util.Constants.ID;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auditing.model.AuditLog;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.request.PostSearchRequestBuilder;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.rs.audit.util.DataplaneAuditHelper;
import org.cdpg.dx.rs.download.model.GetRequestModel;
import org.cdpg.dx.rs.download.service.DownloadService;
import org.cdpg.dx.validations.idhandler.GetIdFromPathHandler;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessApplicableFilterHandlerNgsild;

public class DownloadController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(DownloadController.class);
  private final DownloadService downloadService;
  private final GetIdFromPathHandler getIdFromPathHandler = new GetIdFromPathHandler();
  private final ItemAccessApplicableFilterHandlerNgsild itemAccessApplicableFilterHandlerNgsild;
  private final URNGenerator urnGenerator;
  private final AuditingHandler auditingHandler;

  public DownloadController(
      DownloadService downloadService,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      AuditingHandler auditingHandler) {
    this.downloadService = downloadService;
    this.urnGenerator = urnGenerator;
    this.itemAccessApplicableFilterHandlerNgsild =
        new ItemAccessApplicableFilterHandlerNgsild(controlPlaneDomain);
    this.auditingHandler = auditingHandler;
  }

  @Override
  public void register(RouterBuilder builder) {
    builder
        .operation(DOWNLOAD_ID_ENTITY_DATA)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(itemAccessApplicableFilterHandlerNgsild)
        .handler(this::handleDownloadIdGetData);
    builder
        .operation(DOWNLOAD_PUT_SEARCH_DATA)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(itemAccessApplicableFilterHandlerNgsild)
        .handler(this::handleDownloadIdPostData);
    LOGGER.debug("Download Controller deployed and route registered.");
  }

  private void handleDownloadIdPostData(RoutingContext routingContext) {
    HttpServerResponse response = routingContext.response();
    response
        .putHeader("Access-Control-Allow-Origin", "*")
        .putHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        .putHeader("Access-Control-Allow-Methods", "GET, POST,PUT, DELETE, OPTIONS")
        .putHeader("Content-Type", "text/csv")
        .putHeader("Content-Disposition", "attachment; filename=\"data_report.csv\"")
        .setChunked(true);
    String id = routingContext.pathParam(ID);
    try {
      SearchQuery searchQuery =
          PostSearchRequestBuilder.fromRoutingContext(routingContext)
              .setAssetSearch(false)
              .setCountApi(false)
              .build();
      // Use scroll-based streaming for POST download
      downloadService
          .streamElasticDataCsvScroll(searchQuery, id)
          .onSuccess(
              csvStream -> {
                if (csvStream == null) {
                  response.end();
                  return;
                }
                csvStream
                    .exceptionHandler(
                        err -> {
                          LOGGER.error("Failed to stream CSV", err);
                          routingContext.fail(err);
                        })
                    .handler(buffer -> response.write(buffer))
                    .endHandler(
                        v -> {
                          AuditLog auditLog =
                              DataplaneAuditHelper.createAuditingLogs(
                                  RoutingContextHelper.getItemMetaData(routingContext),
                                  id,
                                  RoutingContextHelper.getRequestPath(routingContext),
                                  "POST",
                                  routingContext.user().subject(),
                                  NGSILD,
                                  "consumer",
                                  VIEW);
                          RoutingContextHelper.setAuditingLog(routingContext, auditLog);
                          response.end();
                        });
              })
          .onFailure(
              err -> {
                LOGGER.error("Failed to stream CSV", err);
                routingContext.fail(err);
              });
    } catch (Exception e) {
      LOGGER.error("Error processing search request: {}", e.getMessage(), e);
    }
  }

  private void handleDownloadIdGetData(RoutingContext routingContext) {
    HttpServerResponse response = routingContext.response();
    response
        .putHeader("Access-Control-Allow-Origin", "*")
        .putHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        .putHeader("Access-Control-Allow-Methods", "GET, POST,PUT, DELETE, OPTIONS")
        .putHeader("Content-Type", "text/csv")
        .putHeader("Content-Disposition", "attachment; filename=\"data_report.csv\"")
        .setChunked(true);
    String id = routingContext.pathParam(ID);
    MultiMap params = routingContext.queryParams();
    int size = getSize(params);
    int page = getPage(params);
    String time = routingContext.queryParams().get("time");
    String endTime = routingContext.queryParams().get("endTime");
    String timeRel = routingContext.queryParams().get("timeRel");
    String sort = routingContext.queryParams().get("sort");
    String sortBy;
    if (sort == null) {
      sortBy = "observationDateTime:desc";
    } else {
      sortBy = sort;
    }
    String sortOrder = sortBy.split(":")[1];
    sortBy = sortBy.split(":")[0];

    JsonArray applicableFilters = RoutingContextHelper.getApplicableFilter(routingContext);
    boolean attrFilter = false;
    if (applicableFilters.contains("ATTR") && !applicableFilters.contains("TEMPORAL")) {
      attrFilter = true;
    }
    GetRequestModel getRequestModel =
        new GetRequestModel(id, size, page, time, endTime, timeRel, sortBy, sortOrder, attrFilter);

    // Use scroll-based streaming for GET download
    downloadService
        .streamElasticDataCsvScroll(getRequestModel)
        .onSuccess(
            csvStream -> {
              if (csvStream == null) {
                response.end();
                return;
              }
              csvStream
                  .exceptionHandler(
                      err -> {
                        LOGGER.error("Failed to stream CSV", err);
                        routingContext.fail(err);
                      })
                  .handler(buffer -> response.write(buffer))
                  .endHandler(
                      v -> {
                        AuditLog auditLog =
                            DataplaneAuditHelper.createAuditingLogs(
                                RoutingContextHelper.getItemMetaData(routingContext),
                                id,
                                RoutingContextHelper.getRequestPath(routingContext),
                                "GET",
                                routingContext.user().subject(),
                                NGSILD,
                                "consumer",
                                VIEW);
                        RoutingContextHelper.setAuditingLog(routingContext, auditLog);
                        response.end();
                      });
            })
        .onFailure(
            err -> {
              LOGGER.error("Failed to stream CSV", err);
              routingContext.fail(err);
            });
  }

  public int getSize(MultiMap params) {
    return params.get(SIZE_KEY) != null ? Integer.parseInt(params.get(SIZE_KEY)) : 10;
  }

  public int getPage(MultiMap params) {
    return params.get(PAGE_KEY) != null ? Integer.parseInt(params.get(PAGE_KEY)) : 1;
  }
}
