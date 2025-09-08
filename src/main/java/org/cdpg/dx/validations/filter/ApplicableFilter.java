package org.cdpg.dx.validations.filter;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.exception.DxInternalServerErrorException;
import org.cdpg.dx.common.util.RoutingContextHelper;

public class ApplicableFilter implements Handler<RoutingContext> {
  private static final Logger LOGGER = LogManager.getLogger(ApplicableFilter.class);
  private final WebClient webClient;
  private final String getItemUrl;

  public ApplicableFilter(String controlPlaneDomain) {
    this.webClient = WebClient.create(Vertx.vertx(), new WebClientOptions().setTrustAll(true));
    this.getItemUrl = controlPlaneDomain + "/iudx/v2/cat/item";
  }

  @Override
  public void handle(RoutingContext context) {
    LOGGER.debug("ApplicableFilter: Checking if filter is applicable");
    String itemId = RoutingContextHelper.getId(context);
    getApplicableFilter(itemId)
        .onSuccess(
            v -> {
              if (v == null || v.isEmpty()) {
                context.fail(new DxBadRequestException("No applicable filter found for the item"));
                return;
              }
              RoutingContextHelper.setApplicableFilter(context, v);
              context.next();
            })
        .onFailure(
            err -> {
              LOGGER.error("failed {}", err.getMessage());
              context.fail(err);
            });
  }

  private Future<JsonArray> getApplicableFilter(String itemId) {
    LOGGER.debug("Fetching item metadata for itemId: {}", itemId);
    Promise<JsonArray> promise = Promise.promise();
    HttpRequest<?> getRequest = webClient.getAbs(getItemUrl);

    getRequest
        .addQueryParam("id", itemId)
        .send()
        .onSuccess(
            resp -> {
              LOGGER.debug("Item metadata fetch response status: {}", resp.statusCode());
              if (resp.statusCode() == 200) {
                JsonObject responseJson = resp.bodyAsJsonObject();
                JsonObject resultObj = responseJson.getJsonArray("result").getJsonObject(0);
                if (resultObj != null && !resultObj.isEmpty()) {
                  LOGGER.debug(
                      "Item applicable list: {}",
                      resultObj
                          .getJsonArray("resourceServer")
                          .getJsonObject(0)
                          .getJsonArray("accessTypes"));
                  JsonArray result =
                      resultObj
                          .getJsonArray("resourceServer")
                          .getJsonObject(0)
                          .getJsonArray("accessTypes");
                  promise.complete(result);
                }
              } else {
                promise.fail(
                    new DxInternalServerErrorException(
                        "Failed to fetch item metadata, status: " + resp.statusCode()));
              }
            })
        .onFailure(
            err ->
                promise.fail(
                    new DxInternalServerErrorException(
                        "Item metadata fetch failed: " + err.getMessage())));
    return promise.future();
  }
}
