package org.cdpg.dx.rs.util;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxAuthException;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.exception.DxForbiddenNoAccessException;
import org.cdpg.dx.common.exception.DxInternalServerErrorException;
import org.cdpg.dx.common.util.RoutingContextHelper;

public class CheckItemAccessHandler implements Handler<RoutingContext> {

    private static final Logger LOGGER = LogManager.getLogger(CheckItemAccessHandler.class);
    
    private final WebClient webClient;
    private final String checkAccessRequestUrl;
    
    public CheckItemAccessHandler(String controlPlaneDomain) {
        this.webClient = WebClient.create(Vertx.vertx(), new WebClientOptions().setTrustAll(true));
        this.checkAccessRequestUrl = controlPlaneDomain + "/iudx/acl/apd/v2/access_request/has_access";
    }

    @Override
    public void handle(RoutingContext context) {
        try {
            String bearerToken = RoutingContextHelper.getToken(context)
                .orElseThrow(() -> new DxAuthException("Bearer token is missing in the request"));
            JsonObject requestBody = new JsonObject()
                .put("itemId", RoutingContextHelper.getId(context));
            LOGGER.debug("Making request to endpoint: {}", checkAccessRequestUrl);
            
            webClient.postAbs(checkAccessRequestUrl)
                .putHeader("Authorization", "Bearer " + bearerToken)
                .sendJsonObject(requestBody)
                .onSuccess(response -> {
                    if (response.statusCode() == 200) {
                        LOGGER.debug("Access check successful for endpoint: {}", checkAccessRequestUrl);
                        context.next();
                    } else {
                        LOGGER.error("Access check failed with status: {} for endpoint: {}", 
                                   response.statusCode(), checkAccessRequestUrl);
                        context.fail(new DxForbiddenNoAccessException("Access check failed. "+response.bodyAsJsonObject().getString("detail")));
                    }
                })
                .onFailure(error -> {
                    LOGGER.error("Access check request failed for endpoint: {}", error.getMessage());
                    context.fail(new DxInternalServerErrorException("Access check request failed: " + error.getMessage()));
                });
                
        } catch (Exception e) {
            LOGGER.error("Error in CheckItemAccessHandler", e);
            context.fail(e);
        }
    }
}
