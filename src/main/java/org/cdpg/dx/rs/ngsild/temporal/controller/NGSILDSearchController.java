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
import org.cdpg.dx.rs.ngsild.temporal.service.NGSILDService;
import org.cdpg.dx.rs.ngsild.temporal.util.Util;
import org.cdpg.dx.rs.validation.ngsild.NGSILDParamsValidator;
import org.cdpg.dx.validations.idhandler.GetIdFromBodyHandler;
import org.cdpg.dx.validations.idhandler.GetIdFromParams;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessApplicableFilterHandlerNgsild;

public class NGSILDSearchController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDSearchController.class);
  private final ItemAccessApplicableFilterHandlerNgsild itemAccessApplicableFilterHandlerNgsild;
  private final URNGenerator urnGenerator;
  GetIdFromParams getIdFromParams = new GetIdFromParams();
  GetIdFromBodyHandler getIdFromBodyHandler = new GetIdFromBodyHandler();
  NGSILDParamsValidator ngsildParamsValidator;
  NGSILDService ngsildService;

  public NGSILDSearchController(
      NGSILDService ngsildService,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      int maxDaysSync,
      int maxDaysAsync) {
    this.ngsildService = ngsildService;
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
    builder
        .operation("postTemporalEntitiesSearch")
        .handler(getIdFromBodyHandler)
        .handler(itemAccessApplicableFilterHandlerNgsild)
        .handler(context -> handlePostTemporalEntityDataSearch(context, true));
    builder
        .operation("getEntitiesSpatial")
        .handler(getIdFromParams)
        .handler(itemAccessApplicableFilterHandlerNgsild)
        .handler(context -> handleEntityAttributeDataSearch(context, false));
    builder
        .operation("postEntitiesSearch")
        .handler(getIdFromBodyHandler)
        .handler(itemAccessApplicableFilterHandlerNgsild)
        .handler(context -> handlePostEntityAttributeDataSearch(context, false));
  }

  private void handlePostEntityAttributeDataSearch(RoutingContext context, boolean isTemporal) {
    LOGGER.debug("Handling handlePostEntityAttributeDataSearch data query");
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(context);
    /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    JsonObject bodyJson =
        context.body() != null && context.body().asJsonObject() != null
            ? context.body().asJsonObject()
            : new JsonObject();
    LOGGER.info("Info: request Json :: " + bodyJson);
    JsonObject requestJson = bodyJson.copy();
    MultiMap params = context.request().params(true);
    MultiMap requestConvertedParam = Util.convertBodyToParams(bodyJson);
    LOGGER.debug("Info: Converted Params :: " + requestConvertedParam);

    try {
      ngsildParamsValidator.validateQueryParamsEntities(requestConvertedParam);
      ngsildParamsValidator.validateQueryParamsPost(params);
      ngsildParamsValidator.isValidQueryWithFilters(requestConvertedParam, applicableFilter);
      // temporal params validation
      ngsildParamsValidator.validateTemporal(
          requestConvertedParam.get(NGSILDQUERY_TIMEREL),
          requestConvertedParam.get(NGSILDQUERY_TIMEAT),
          requestConvertedParam.get(NGSILDQUERY_ENDTIMEAT),
          requestConvertedParam.get(NGSILDQUERY_TIMEPROPERTY),
          false,
          isTemporal);
      // Validate geo Fields
      ngsildParamsValidator.validateGeometry(
          requestConvertedParam.get(NGSILDQUERY_GEOPROPERTY),
          requestConvertedParam.get(NGSILDQUERY_GEOMETRY),
          requestConvertedParam.get(NGSILDQUERY_COORDINATES));
      ngsildParamsValidator.validateQ(requestConvertedParam.get(NGSILDQUERY_Q));
      ngsildParamsValidator.validatePick(requestConvertedParam.get(NGSILDQUERY_PICK));
      ngsildParamsValidator.validateOmit(requestConvertedParam.get(NGSILDQUERY_OMIT));
      params.entries().forEach(e -> requestJson.put(e.getKey(), e.getValue()));
      LOGGER.debug("nsgildParamsValidator completed");
    } catch (DxBadRequestException e) {
      context.fail(e);
      return;
    }

    HttpServerResponse response = context.response();
    NGSILDQueryParams ngsildQueryParams = new NGSILDQueryParams(requestJson);

    if (ngsildQueryParams.isCount()) {
      ngsildService
          .getEntitiesAttributeSearchCount(ngsildQueryParams)
          .onSuccess(
              postEntitiesCount -> {
                JsonObject result = new JsonObject();
                result.put("type", "CountResult");
                result.put("value", postEntitiesCount);
                response
                    .putHeader("Content-Type", "application/json")
                    .putHeader(HEADER_ALLOW_ORIGIN, "*")
                    .putHeader(
                        "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                    .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    .putHeader(NGSILD_RESULTS_COUNT, String.valueOf(postEntitiesCount))
                    .setStatusCode(200)
                    .end(result.encode());
              })
          .onFailure(
              err -> {
                LOGGER.error("Count request failed: {}", err.getMessage(), err);
                context.fail(err);
              });
    } else {
      ngsildService
          .getEntitiesAttributeSearchData(ngsildQueryParams)
          .onSuccess(
              getEntityData -> {
                response
                    .putHeader("Content-Type", "application/json")
                    .putHeader(HEADER_ALLOW_ORIGIN, "*")
                    .putHeader(
                        "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                    .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    .putHeader(NGSILD_RESULTS_COUNT, String.valueOf(getEntityData.getTotalHits()))
                    .putHeader(NGSILD_LIMIT, String.valueOf(ngsildQueryParams.getPageSize()))
                    .putHeader(NGSILD_OFFSET, String.valueOf(ngsildQueryParams.getPageFrom()))
                    .setStatusCode(200)
                    /*.end(JsonObject.mapFrom(response).encode());*/
                    .end(getEntityData.getElasticsearchResponses().toString());
                /*ResponseBuilder.sendSuccess(
                routingContext,
                getTemporalEntityData.getElasticsearchResponses(),
                getTemporalEntityData.getPaginationInfo(),
                urnGenerator);*/
              })
          .onFailure(
              err -> {
                LOGGER.error("Search request failed: {}", err.getMessage(), err);
                context.fail(err);
              });
    }
  }

  private void handleEntityAttributeDataSearch(RoutingContext routingContext, boolean isTemporal) {
    LOGGER.debug("Handling entities attribute GET data query");

    MultiMap params = routingContext.request().params(true);
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(routingContext);
      /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    try {
      ngsildParamsValidator.validateQueryParamsEntities(params);
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
      ngsildService
          .getEntitiesAttributeSearchCount(ngsildQueryParams)
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
      ngsildService
          .getEntitiesAttributeSearchData(ngsildQueryParams)
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

  private void handlePostTemporalEntityDataSearch(RoutingContext context, boolean isTemporal) {
    LOGGER.debug("Handling Temporal entities POST data query");
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(context);
    /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    JsonObject bodyJson =
        context.body() != null && context.body().asJsonObject() != null
            ? context.body().asJsonObject()
            : new JsonObject();
    LOGGER.info("Info: request Json :: " + bodyJson);
    JsonObject requestJson = bodyJson.copy();
    MultiMap params = context.request().params(true);
    MultiMap requestConvertedParam = Util.convertBodyToParams(bodyJson);
    LOGGER.debug("Info: Converted Params :: " + requestConvertedParam);

    try {
      ngsildParamsValidator.validateQueryParamsTemporalEntities(requestConvertedParam);
      ngsildParamsValidator.validateQueryParamsPost(params);
      ngsildParamsValidator.isValidQueryWithFilters(requestConvertedParam, applicableFilter);
      // temporal params validation
      ngsildParamsValidator.validateTemporal(
          requestConvertedParam.get(NGSILDQUERY_TIMEREL),
          requestConvertedParam.get(NGSILDQUERY_TIMEAT),
          requestConvertedParam.get(NGSILDQUERY_ENDTIMEAT),
          requestConvertedParam.get(NGSILDQUERY_TIMEPROPERTY),
          false,
          isTemporal);
      // Validate geo Fields
      ngsildParamsValidator.validateGeometry(
          requestConvertedParam.get(NGSILDQUERY_GEOPROPERTY),
          requestConvertedParam.get(NGSILDQUERY_GEOMETRY),
          requestConvertedParam.get(NGSILDQUERY_COORDINATES));
      ngsildParamsValidator.validateQ(requestConvertedParam.get(NGSILDQUERY_Q));
      ngsildParamsValidator.validatePick(requestConvertedParam.get(NGSILDQUERY_PICK));
      ngsildParamsValidator.validateOmit(requestConvertedParam.get(NGSILDQUERY_OMIT));
      params.entries().forEach(e -> requestJson.put(e.getKey(), e.getValue()));
      LOGGER.debug("nsgildParamsValidator completed");
    } catch (DxBadRequestException e) {
      context.fail(e);
      return;
    }

    HttpServerResponse response = context.response();
    NGSILDQueryParams ngsildQueryParams = new NGSILDQueryParams(requestJson);

    if (ngsildQueryParams.isCount()) {
      ngsildService
          .getTemporalSearchCount(ngsildQueryParams)
          .onSuccess(
              postTemporalEntitiesCount -> {
                JsonObject result = new JsonObject();
                result.put("type", "CountResult");
                result.put("value", postTemporalEntitiesCount);
                response
                    .putHeader("Content-Type", "application/json")
                    .putHeader(HEADER_ALLOW_ORIGIN, "*")
                    .putHeader(
                        "Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                    .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
                    .putHeader(NGSILD_RESULTS_COUNT, String.valueOf(postTemporalEntitiesCount))
                    .setStatusCode(200)
                    .end(result.encode());
              })
          .onFailure(
              err -> {
                LOGGER.error("Count request failed: {}", err.getMessage(), err);
                context.fail(err);
              });
    } else {
      ngsildService
          .getTemporalSearchData(ngsildQueryParams)
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
                context.fail(err);
              });
    }
  }

  private void handleTemporalEntityDataSearch(RoutingContext routingContext, boolean isTemporal) {
    LOGGER.debug("Handling Temporal entities GET data query");

    MultiMap params = routingContext.request().params(true);
    /*params.add(NGSILD_LINK, routingContext.request().getHeader(NGSILD_LINK));*/
    /*MultiMap headers = routingContext.request().headers();*/
    JsonArray applicableFilter = RoutingContextHelper.getApplicableFilter(routingContext);
      /*new JsonArray().add("TEMPORAL").add("ATTR");*/
    try {
      ngsildParamsValidator.validateQueryParamsTemporalEntities(params);
      /*ngsildParamsValidator.validateHeaders(headers);*/
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
      ngsildService
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
      ngsildService
          .getTemporalSearchData(ngsildQueryParams)
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
