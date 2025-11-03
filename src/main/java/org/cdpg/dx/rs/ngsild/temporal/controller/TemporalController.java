package org.cdpg.dx.rs.ngsild.temporal.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.HEADER_ALLOW_ORIGIN;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.*;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;
import org.cdpg.dx.rs.ngsild.temporal.service.TemporalService;
import org.cdpg.dx.rs.validation.ngsild.NGSILDParamsValidator;
import org.cdpg.dx.validations.idhandler.GetIdFromParams;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessApplicableFilterHandlerNgsild;

public class TemporalController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(TemporalController.class);
  private final ItemAccessApplicableFilterHandlerNgsild itemAccessApplicableFilterHandlerNgsild;
  private final URNGenerator urnGenerator;
  GetIdFromParams getIdFromParams = new GetIdFromParams();
  NGSILDParamsValidator ngsildParamsValidator;
  TemporalService temporalService;

  public TemporalController(
      TemporalService temporalService,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      int maxDaysSync,
      int maxDaysAsync) {
    this.temporalService = temporalService;
    this.itemAccessApplicableFilterHandlerNgsild =
        new ItemAccessApplicableFilterHandlerNgsild(controlPlaneDomain);
    this.ngsildParamsValidator = new NGSILDParamsValidator(maxDaysSync, maxDaysAsync);
    this.urnGenerator = urnGenerator;
  }

  @Override
  public void register(RouterBuilder builder) {
    builder
        .operation("getTemporalEntities")
        .handler(getIdFromParams)
        .handler(itemAccessApplicableFilterHandlerNgsild)
        .handler(context -> handleTemporalEntityDataSearch(context, true));
  }

  private void handleTemporalEntityDataSearch(RoutingContext routingContext, boolean isTemporal) {
    LOGGER.debug("Handling Temporal entities GET data query");

    MultiMap params = routingContext.request().params(true);
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(routingContext);
    /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    try {
      ngsildParamsValidator.validateQueryParams(params);
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
      temporalService
          .getTemporalSearchCount(ngsildQueryParams)
          .onSuccess(
              getTemporalEntityCount -> {
                JsonObject result = new JsonObject();
                result.put("type", "CountResult");
                result.put("value", getTemporalEntityCount);
                response
                    .putHeader("Content-Type", "application/json")
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
      temporalService
          .getTemporalSearch(ngsildQueryParams)
          .onSuccess(
              getTemporalEntityData -> {
                response
                    .putHeader("Content-Type", "application/json")
                    .putHeader(HEADER_ALLOW_ORIGIN, "*")
                    .putHeader(
                        "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                    .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    .putHeader(
                        NGSILD_RESULTS_COUNT, String.valueOf(getTemporalEntityData.getTotalHits()))
                    .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                    .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
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
    }
  }
}
