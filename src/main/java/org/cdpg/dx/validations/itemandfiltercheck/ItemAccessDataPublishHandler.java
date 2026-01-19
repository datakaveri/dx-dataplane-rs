package org.cdpg.dx.validations.itemandfiltercheck;

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
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.exception.DxForbiddenNoAccessException;
import org.cdpg.dx.common.exception.DxInternalServerErrorException;
import org.cdpg.dx.common.util.RoutingContextHelper;

public class ItemAccessDataPublishHandler implements Handler<RoutingContext> {
  private static final Logger LOGGER = LogManager.getLogger(ItemAccessDataPublishHandler.class);
  private final WebClient webClient;
  private final String checkItemAndFilterUrl;

  public ItemAccessDataPublishHandler(String checkItemAndFilterUrl) {
    this.webClient = WebClient.create(Vertx.vertx(), new WebClientOptions().setTrustAll(true));
    this.checkItemAndFilterUrl = checkItemAndFilterUrl + "/iudx/v2/cat/item/access";
  }

  @Override
  public void handle(RoutingContext context) {
    LOGGER.info("Handling Item Access Data Publish Request");
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

    getData(itemId, bearerToken)
        .onSuccess(
            result -> {
              RoutingContextHelper.setItemMetaData(context, result);
              RoutingContextHelper.setIid(context, result.getString("id"));
              RoutingContextHelper.setAccessPolicy(context, result.getString("accessPolicy"));
              RoutingContextHelper.setOwnerUserId(context, result.getString("ownerUserId", null));
              context.next();
            })
        .onFailure(
            err -> {
              LOGGER.error("failed {}", err.getMessage());
              context.fail(err);
            });
  }

  private Future<JsonObject> getData(String itemId, String bearerToken) {
    LOGGER.debug("Fetching item metadata for itemId: {}", itemId);
    Promise<JsonObject> promise = Promise.promise();
    HttpRequest<?> getRequest = webClient.getAbs(checkItemAndFilterUrl);
    getRequest
        .addQueryParam("id", itemId)
        .putHeader("Authorization", "Bearer " + bearerToken)
        .send()
        .onSuccess(
            resp -> {
              LOGGER.debug("Item metadata fetch response status: {}", resp.statusCode());
              if (resp.statusCode() == 200) {
                try {
                  JsonObject responseJson = resp.bodyAsJsonObject();
                  JsonObject resultObj = responseJson.getJsonArray("result").getJsonObject(0);
                  if (resultObj != null && !resultObj.isEmpty()) {
                    promise.complete(resultObj);
                  } else {
                    LOGGER.error("No response from control plane");
                    promise.fail(new DxBadRequestException("No response from control plane"));
                  }
                } catch (Exception e) {
                  LOGGER.error("Error in from control plane {}", e.getMessage());
                  promise.fail(new DxInternalServerErrorException("failed: " + e.getMessage()));
                }
              } else {
                promise.fail(
                    new DxForbiddenNoAccessException(
                        "Access check failed " + resp.bodyAsJsonObject().getString("detail")));
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
