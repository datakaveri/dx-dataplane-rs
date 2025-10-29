package org.cdpg.dx.rs.ngsild.temporal.controller;

import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.*;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;
import org.cdpg.dx.rs.ngsild.temporal.service.TemporalService;
import org.cdpg.dx.rs.validation.ngsild.NGSILDParamsValidator;
import org.cdpg.dx.validations.idhandler.GetIdFromParams;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessApplicableFilterHandlerNgsild;

public class TemporalController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(TemporalController.class);
  private final ItemAccessApplicableFilterHandlerNgsild itemAccessApplicableFilterHandlerNgsild =
      new ItemAccessApplicableFilterHandlerNgsild("https://v2.dev.controlplane.iudx.io");
  GetIdFromParams getIdFromParams = new GetIdFromParams();
  NGSILDParamsValidator ngsildParamsValidator = new NGSILDParamsValidator(365, 10);
  TemporalService temporalService;

  public TemporalController(TemporalService temporalService) {
    this.temporalService = temporalService;
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
      LOGGER.debug("nsgildParamsValidator:");
    } catch (DxBadRequestException e) {
      routingContext.fail(e);
      return;
    }

    /*TemporalService temporalService = new TemporalServiceImpl();
    temporalService.getTemporalSearch(params);*/

    NGSILDQueryParams ngsildQueryParams = new NGSILDQueryParams(params);
    temporalService.getTemporalSearch(ngsildQueryParams);

    // TemporalGetRequest temporalGetRequest = TemporalEntitiesValidation.validateParam(params);
    //        LOGGER.debug("Validated TemporalGetRequest: {}", temporalGetRequest.toJson());

  }
}
