package org.cdpg.dx.validations.itemandfiltercheck;

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
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.exception.DxForbiddenNoAccessException;
import org.cdpg.dx.common.exception.DxInternalServerErrorException;
import org.cdpg.dx.common.util.RoutingContextHelper;

public class ItemAccessApplicableFilterHandlerGateway implements Handler<RoutingContext> {

  private static final Logger LOGGER =
      LogManager.getLogger(ItemAccessApplicableFilterHandlerGateway.class);
  private final WebClient webClient;
  private final String checkItemAndFilterUrl;

  public ItemAccessApplicableFilterHandlerGateway(String controlPlaneDomain) {
    this.webClient = WebClient.create(Vertx.vertx(), new WebClientOptions().setTrustAll(true));
    this.checkItemAndFilterUrl = controlPlaneDomain + "/iudx/v2/cat/item/access";
  }

  @Override
  public void handle(RoutingContext context) {
    LOGGER.info("Starting ItemAccessApplicableFilterHandlerGateway");

    if (context.user().principal().containsKey("cons")) {
      LOGGER.debug("Processing access token");
      JsonArray resourceServers = context.user().principal().getJsonArray("resourceServer");
      JsonObject ngsiLdServer =
          Optional.ofNullable(resourceServers)
              .filter(rs -> !rs.isEmpty())
              .orElseThrow(() -> new DxBadRequestException("No resource server information found"))
              .stream()
              .map(JsonObject.class::cast)
              .filter(rs -> "GATEWAY".equalsIgnoreCase(rs.getString("name")))
              .findFirst()
              .orElseThrow(() -> new DxBadRequestException("GATEWAY resource server not found"));

      JsonArray accessTypes =
          Optional.ofNullable(ngsiLdServer.getJsonArray("queryTypes"))
              .filter(at -> !at.isEmpty())
              .orElseThrow(
                  () ->
                      new DxBadRequestException(
                          "No queryTypes types(filters) found for GATEWAY server"));
      RoutingContextHelper.setItemMetaData(context, context.user().principal());
      RoutingContextHelper.setApplicableFilter(context, accessTypes);
      context.next();
      return;
    } else {
      LOGGER.debug("processing with control plane call");
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
      getApplicableFilter(itemId, bearerToken)
          .onSuccess(
              result -> {
                JsonArray resourceServers = result.getJsonArray("resourceServer");
                JsonObject ngsiLdServer =
                    Optional.ofNullable(resourceServers)
                        .filter(rs -> !rs.isEmpty())
                        .orElseThrow(
                            () -> new DxBadRequestException("No resource server information found"))
                        .stream()
                        .map(JsonObject.class::cast)
                        .filter(rs -> "GATEWAY".equalsIgnoreCase(rs.getString("name")))
                        .findFirst()
                        .orElseThrow(
                            () -> new DxBadRequestException("GATEWAY resource server not found"));

                JsonArray accessTypes =
                    Optional.ofNullable(ngsiLdServer.getJsonArray("queryTypes"))
                        .filter(at -> !at.isEmpty())
                        .orElseThrow(
                            () ->
                                new DxBadRequestException(
                                    "No queryTypes types(filters) found for GATEWAY server"));
                RoutingContextHelper.setApplicableFilter(context, accessTypes);
                RoutingContextHelper.setItemMetaData(context, result);
                context.next();
              })
          .onFailure(
              err -> {
                LOGGER.error("failed {}", err.getMessage());
                context.fail(err);
              });
    }
  }

  private Future<JsonObject> getApplicableFilter(String itemId, String bearerToken) {
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
                    LOGGER.debug(
                        "Item applicable filter list: {}",
                        resultObj.getJsonArray("resourceServer"));
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
