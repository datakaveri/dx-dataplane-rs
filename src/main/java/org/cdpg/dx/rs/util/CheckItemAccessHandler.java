package org.cdpg.dx.rs.util;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxForbiddenNoAccessException;
import org.cdpg.dx.common.exception.DxInternalServerErrorException;
import org.cdpg.dx.common.util.RoutingContextHelper;

public class CheckItemAccessHandler implements Handler<RoutingContext> {

    private static final Logger LOGGER = LogManager.getLogger(CheckItemAccessHandler.class);
    private final WebClient webClient;
    private final String checkAccessRequestUrl;
    private final String checkItemUrl;

    public CheckItemAccessHandler(String controlPlaneDomain) {
        this.webClient = WebClient.create(Vertx.vertx(), new WebClientOptions().setTrustAll(true));
        this.checkAccessRequestUrl = controlPlaneDomain + "/iudx/acl/apd/v2/access_request/has_access";
        this.checkItemUrl= controlPlaneDomain + "/iudx/v2/cat/item";
    }

    @Override
    public void handle(RoutingContext context) {
        LOGGER.debug("Starting access verification process");
        String itemId;
        String bearerToken;
        try {
            itemId = RoutingContextHelper.getId(context);
            bearerToken = RoutingContextHelper.getToken(context).orElse(null);
        } catch (Exception e) {
            LOGGER.error("Error extracting request parameters", e);
            context.fail(e);
            return;
        }

        isItemOpen(itemId, bearerToken)
                .compose(isItemOpen -> {
                    if (isItemOpen) {
                        LOGGER.debug("Item accessPolicy is OPEN. Skipping access check.");
                        return Future.succeededFuture();
                    } else {
                        return performPostAccessCheck(itemId, bearerToken);
                    }
                })
                .onSuccess(v -> context.next())
                .onFailure(err -> {
                    LOGGER.error("Access verification failed {}", err.getMessage());
                    context.fail(err);
                });
    }

    private Future<Boolean> isItemOpen(String itemId, String bearerToken) {
        LOGGER.debug("Fetching item metadata for itemId: {}", itemId);
        Promise<Boolean> promise = Promise.promise();
        HttpRequest<?> getRequest = webClient.getAbs(checkItemUrl);
        if (bearerToken != null) {
            LOGGER.debug("Token Provided, adding Authorization header");
            getRequest.putHeader("Authorization", "Bearer " + bearerToken);
        }

        getRequest.addQueryParam("id",itemId).send()
                .onSuccess(resp -> {
                    LOGGER.debug("Item metadata fetch response status: {}", resp.statusCode());
                    if (resp.statusCode() == 200) {
                        JsonObject responseJson = resp.bodyAsJsonObject();
                        JsonObject resultObj = responseJson.getJsonArray("result").getJsonObject(0);
                        String accessPolicy = "OPEN";
                        boolean isOpen=false;
                        if (resultObj != null && !resultObj.isEmpty()) {
                            isOpen=resultObj.containsKey("accessPolicy") && resultObj.getString("accessPolicy").equals(accessPolicy);
                            accessPolicy= resultObj.getString("accessPolicy");
                        }
                        LOGGER.debug("Item accessPolicy: {}, isOpen: {}", accessPolicy, isOpen);
                        promise.complete(isOpen);
                    } else {
                        promise.fail(new DxInternalServerErrorException(
                                "Failed to fetch item metadata, status: " + resp.statusCode()));
                    }
                })
                .onFailure(err -> promise.fail(
                        new DxInternalServerErrorException("Item metadata fetch failed: " + err.getMessage())));
        return promise.future();
    }

    private Future<Void> performPostAccessCheck(String itemId, String bearerToken) {
        Promise<Void> promise = Promise.promise();
        JsonObject requestBody = new JsonObject().put("itemId", itemId);

        webClient.postAbs(checkAccessRequestUrl)
                .putHeader("Authorization", "Bearer " + bearerToken)
                .sendJsonObject(requestBody)
                .onSuccess(resp -> {
                    if (resp.statusCode() == 200) {
                        promise.complete();
                    } else {
                        promise.fail(new DxForbiddenNoAccessException(
                                "Access check failed " + resp.bodyAsJsonObject().getString("detail")));
                    }
                })
                .onFailure(err -> promise.fail(
                        new DxInternalServerErrorException("Access check request failed: " + err.getMessage())));
        return promise.future();
    }
}
