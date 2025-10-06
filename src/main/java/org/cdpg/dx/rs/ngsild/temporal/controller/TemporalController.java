package org.cdpg.dx.rs.ngsild.temporal.controller;

import io.vertx.core.MultiMap;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.rs.query.QueryRequest;
import org.cdpg.dx.rs.query.Util;
import org.cdpg.dx.rs.validation.ngsild.TemporalEntitiesValidation;

public class TemporalController implements ApiController {
    private static final Logger LOGGER = LogManager.getLogger(TemporalController.class);
    @Override
    public void register(RouterBuilder builder) {
        builder
                .operation("/temporal/entities")
                /*.handler(getIdFromPathHandler)*/
                /*.handler(itemAccessApplicableFilterHandler)*/
                .handler(this::handleTemporalEntityDataSearch);
    }

    private void handleTemporalEntityDataSearch(RoutingContext routingContext) {
        LOGGER.debug("Handling Temporal entities GET data query");

        MultiMap params = routingContext.queryParams();
        new TemporalEntitiesValidation(params);
        QueryRequest queryRequest = Util.queryRequestFromParams(params);

    }
}
