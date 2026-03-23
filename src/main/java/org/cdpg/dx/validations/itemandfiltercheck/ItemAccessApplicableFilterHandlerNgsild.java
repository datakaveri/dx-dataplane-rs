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
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.exception.DxForbiddenNoAccessException;
import org.cdpg.dx.common.exception.DxInternalServerErrorException;
import org.cdpg.dx.common.util.RoutingContextHelper;

public class ItemAccessApplicableFilterHandlerNgsild implements Handler<RoutingContext> {

  private static final Logger LOGGER =
      LogManager.getLogger(ItemAccessApplicableFilterHandlerNgsild.class);
  private final WebClient webClient;
  private final String checkItemAndFilterUrl;

  public ItemAccessApplicableFilterHandlerNgsild(String controlPlaneDomain) {
    this.webClient = WebClient.create(Vertx.vertx(), new WebClientOptions().setTrustAll(true));
    this.checkItemAndFilterUrl = controlPlaneDomain + "/iudx/v2/cat/item/access";
  }

  @Override
  public void handle(RoutingContext context) {
    LOGGER.info("Starting ItemAccessApplicableFilterHandlerNgsild");

    if (hasAccessPayload(context.user().principal())) {
      LOGGER.debug("Processing access token");
      try {
        JsonArray resourceServers = context.user().principal().getJsonArray("resourceServer");
        JsonObject ngsiLdServer =
            Optional.ofNullable(resourceServers)
                .filter(rs -> !rs.isEmpty())
                .orElseThrow(
                    () -> new DxBadRequestException("No resource server information found"))
                .stream()
                .map(JsonObject.class::cast)
                .filter(rs -> "NGSI-LD".equalsIgnoreCase(rs.getString("name")))
                .findFirst()
                .orElseThrow(() -> new DxBadRequestException("NGSI-LD resource server not found"));

        JsonArray queryTypes =
            Optional.ofNullable(ngsiLdServer.getJsonArray("queryTypes"))
                .filter(at -> !at.isEmpty())
                .orElseThrow(
                    () ->
                        new DxBadRequestException(
                            "No queryTypes types(filters) found for NGSI-LD server"));
        JsonArray allowedAttributes =
            Optional.ofNullable(getCons(context.user().principal()))
                .map(cons -> cons.getJsonArray("allowedAttributes"))
                .orElse(new JsonArray());
        String accessPolicy = context.user().principal().getString("accessPolicy");
        validateApiAccessType(context.user().principal(), ngsiLdServer, accessPolicy);

        RoutingContextHelper.setItemMetaData(context, context.user().principal());
        RoutingContextHelper.setApplicableFilter(context, queryTypes);
        RoutingContextHelper.setAllowedAttributes(context, allowedAttributes);
        RoutingContextHelper.setIid(context, context.user().principal().getString("iid"));
        RoutingContextHelper.setAccessPolicy(context, accessPolicy);
        RoutingContextHelper.setPolicyId(
            context, getPolicyIdFromPolicies(context.user().principal()));
        context.next();
        return;
      } catch (Exception e) {
        LOGGER.error("Error processing access token {}", e.getMessage());
        context.fail(e);
        return;
      }
    } else {
      LOGGER.debug("processing with control plane call");
      String itemId;
      String bearerToken;
      String did;
      boolean isDelegator;
      try {
        did = Optional.ofNullable(context.request().getHeader("did")).orElse("");
        if (did != null && !did.isEmpty()) {
          isDelegator = true;
          RoutingContextHelper.setDid(context, did);
        } else {
          isDelegator = false;
        }
        itemId = RoutingContextHelper.getId(context);
        bearerToken = RoutingContextHelper.getToken(context).orElse(null);
      } catch (Exception e) {
        LOGGER.error("Error extracting request parameters", e);
        context.fail(e);
        return;
      }
      getApplicableFilter(itemId, bearerToken, isDelegator, did)
          .onSuccess(
              result -> {
                try {
                  JsonArray resourceServers = result.getJsonArray("resourceServer");
                  JsonObject ngsiLdServer =
                      Optional.ofNullable(resourceServers)
                          .filter(rs -> !rs.isEmpty())
                          .orElseThrow(
                              () ->
                                  new DxBadRequestException("No resource server information found"))
                          .stream()
                          .map(JsonObject.class::cast)
                          .filter(rs -> "NGSI-LD".equalsIgnoreCase(rs.getString("name")))
                          .findFirst()
                          .orElseThrow(
                              () -> new DxBadRequestException("NGSI-LD resource server not found"));

                  JsonArray queryTypes =
                      Optional.ofNullable(ngsiLdServer.getJsonArray("queryTypes"))
                          .filter(at -> !at.isEmpty())
                          .orElseThrow(
                              () ->
                                  new DxBadRequestException(
                                      "No queryTypes types(filters) found for NGSI-LD server"));
                  JsonArray allowedAttributes =
                      Optional.ofNullable(getCons(result))
                          .map(cons -> cons.getJsonArray("allowedAttributes"))
                          .orElse(new JsonArray());
                  String accessPolicy = result.getString("accessPolicy");
                  validateApiAccessType(result, ngsiLdServer, accessPolicy);

                  RoutingContextHelper.setApplicableFilter(context, queryTypes);
                  RoutingContextHelper.setItemMetaData(context, result);
                  RoutingContextHelper.setAllowedAttributes(context, allowedAttributes);
                  RoutingContextHelper.setIid(context, result.getString("id"));
                  RoutingContextHelper.setAccessPolicy(context, accessPolicy);
                  RoutingContextHelper.setPolicyId(context, getPolicyIdFromPolicies(result));
                  context.next();
                } catch (Exception e) {
                  LOGGER.error("Error processing control plane response {}", e.getMessage());
                  context.fail(e);
                }
              })
          .onFailure(
              err -> {
                LOGGER.error("failed {}", err.getMessage());
                context.fail(err);
              });
    }
  }

  private Future<JsonObject> getApplicableFilter(
      String itemId, String bearerToken, boolean isDelegator, String did) {
    LOGGER.debug("Fetching item metadata for itemId: {}", itemId);
    Promise<JsonObject> promise = Promise.promise();
    HttpRequest<?> getRequest = webClient.getAbs(checkItemAndFilterUrl);

    getRequest.addQueryParam("id", itemId).putHeader("Authorization", "Bearer " + bearerToken);
    if (isDelegator) {
      getRequest.addQueryParam("isDelegator", "true").addQueryParam("did", did);
    }
    getRequest
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

  private void validateApiAccessType(
      JsonObject source, JsonObject selectedResourceServer, String accessPolicy) {
    if (accessPolicy != null
        && ("open".equalsIgnoreCase(accessPolicy) || "public".equalsIgnoreCase(accessPolicy))) {
      return;
    }

    JsonArray accessArray = source.getJsonArray("access");
    if (accessArray == null) {
      JsonObject cons = getCons(source);
      if (cons != null) {
        accessArray = cons.getJsonArray("access");
      }
    }

    if (accessArray == null || accessArray.isEmpty()) {
      throw new DxForbiddenNoAccessException("API accessType not found for restricted resource");
    }

    List<JsonObject> apiAccessEntries =
        accessArray.stream()
            .filter(JsonObject.class::isInstance)
            .map(JsonObject.class::cast)
            .filter(access -> "api".equalsIgnoreCase(access.getString("accessType", "")))
            .toList();

    if (apiAccessEntries.isEmpty()) {
      throw new DxForbiddenNoAccessException(
          "Required accessType 'api' missing for restricted resource");
    }

    if (!apiAccessEntries.isEmpty()) {
      boolean hasValidExpiry =
          apiAccessEntries.stream().anyMatch(access -> !isExpired(access.getLong("expiry", -1L)));
      if (!hasValidExpiry) {
        throw new DxForbiddenNoAccessException("Access token policy has expired");
      }
    }

    LOGGER.info("Restricted access validation passed: api accessType and expiry are valid");
  }

  private boolean containsApi(JsonArray accessTypes) {
    return accessTypes.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .anyMatch(type -> "api".equalsIgnoreCase(type));
  }

  private boolean isExpired(long expiryEpochSeconds) {
    LOGGER.debug(
        "Checking token expiry: expiryEpochSeconds={}, currentEpochSeconds={}",
        expiryEpochSeconds,
        System.currentTimeMillis() / 1000L);
    return expiryEpochSeconds > 0 && expiryEpochSeconds <= (System.currentTimeMillis() / 1000L);
  }

  private boolean hasAccessPayload(JsonObject source) {
    return source != null && source.containsKey("policies");
  }

  private JsonObject getCons(JsonObject source) {
    if (source == null) {
      return null;
    }
    JsonArray policies = source.getJsonArray("policies");
    if (policies == null || policies.isEmpty()) {
      return null;
    }
    for (Object object : policies) {
      if (object instanceof JsonObject policy) {
        JsonObject cons = policy.getJsonObject("cons");
        if (cons != null) {
          return cons;
        }
      }
    }
    return null;
  }

  private String getPolicyIdFromPolicies(JsonObject source) {
    if (source == null) {
      return null;
    }
    JsonArray policies = source.getJsonArray("policies");
    if (policies == null || policies.isEmpty()) {
      return null;
    }
    for (Object object : policies) {
      if (object instanceof JsonObject policy) {
        String policyId = policy.getString("policyId");
        if (policyId != null && !policyId.isBlank()) {
          return policyId;
        }
      }
    }
    return null;
  }
}
