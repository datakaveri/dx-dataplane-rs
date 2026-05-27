package org.cdpg.dx.auth.appid;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.appid.cache.AppIdItemAccessCacheService;
import org.cdpg.dx.auth.appid.client.AppIdVerificationClient;
import org.cdpg.dx.auth.appid.client.KeycloakServiceTokenProvider;
import org.cdpg.dx.auth.appid.handler.AppIdAuthHandler;
import org.cdpg.dx.auth.appid.model.AppIdItemAccessResult;
import org.cdpg.dx.common.exception.DxForbiddenNoAccessException;
import org.cdpg.dx.common.exception.DxInternalServerErrorException;
import org.cdpg.dx.common.util.RoutingContextHelper;

/**
 * Route handler that performs the per-entity gRPC access check for AppId-authenticated requests
 * (Step 2 — authorization).
 *
 * <p>Must be placed in the handler chain AFTER the entity-ID-extraction handler and BEFORE {@link
 * org.cdpg.dx.validations.itemandfiltercheck.ItemAccessApplicableFilterHandlerNgsild} / {@link
 * org.cdpg.dx.validations.itemandfiltercheck.ItemAccessApplicableFilterHandlerGateway}.
 *
 * <p>For JWT-authenticated requests ({@link AppIdAuthHandler#APP_ID_KEY} absent) this handler is a
 * no-op passthrough.
 *
 * <p>For AppId-authenticated requests it calls gRPC {@code CheckItemAccess(appId, entityId)} on
 * dx-controlplane, caches the result, and merges the item metadata ({@code policies}, {@code
 * resourceServer}, {@code accessPolicy}, {@code iid}) into {@code ctx.user().principal()} so the
 * downstream filter handler takes its fast path (no additional HTTP call).
 */
public class AppIdItemAccessHandler implements Handler<RoutingContext> {

  private static final Logger LOGGER = LogManager.getLogger(AppIdItemAccessHandler.class);

  private final AppIdItemAccessCacheService cacheService;
  private final AppIdVerificationClient client;
  private final KeycloakServiceTokenProvider tokenProvider;

  public AppIdItemAccessHandler(
      AppIdItemAccessCacheService cacheService,
      AppIdVerificationClient client,
      KeycloakServiceTokenProvider tokenProvider) {
    this.cacheService = cacheService;
    this.client = client;
    this.tokenProvider = tokenProvider;
  }

  @Override
  public void handle(RoutingContext ctx) {
    String appId = ctx.get(AppIdAuthHandler.APP_ID_KEY);
    if (appId == null) {
      ctx.next();
      return;
    }

    String entityId = RoutingContextHelper.getId(ctx);
    if (entityId == null || entityId.isBlank()) {
      LOGGER.warn("AppId item access check: entity ID missing from routing context");
      ctx.fail(new DxForbiddenNoAccessException("Entity ID not found in request"));
      return;
    }

    // Read did directly from the HTTP header — this handler runs before
    // ItemAccessApplicableFilterHandlerNgsild which would otherwise store it in context.
    String did = ctx.request().getHeader("did");
    boolean hasDid = did != null && !did.isBlank();

    // userId from principal's sub — sent to gRPC so controlplane skips redundant getAppById() DB
    // call.
    // appId is kept separately as the cache key (matches revocation events which carry appId).
    String userId = ctx.user().principal().getString("sub");

    // Skip cache when delegation is involved: delegation grants can be revoked independently
    // of the appId, so a cached success result may no longer be valid.
    if (!hasDid) {
      cacheService
          .get(appId, entityId)
          .ifPresentOrElse(
              result -> {
                LOGGER.debug("AppId item access cache hit appId={} entityId={}", appId, entityId);
                mergeMetadata(ctx, result);
                ctx.next();
              },
              () -> checkWithControlplane(ctx, appId, userId, entityId, ""));
      return;
    }

    checkWithControlplane(ctx, appId, userId, entityId, did);
  }

  private void checkWithControlplane(
      RoutingContext ctx, String appId, String userId, String entityId, String did) {
    tokenProvider
        .getServiceToken()
        .compose(token -> client.checkItemAccess(userId, entityId, did, token))
        .onSuccess(
            response -> {
              if (!response.getSuccess()) {
                LOGGER.warn(
                    "AppId item access denied appId={} entityId={} did={} reason={}",
                    appId,
                    entityId,
                    did,
                    response.getErrorCode());
                ctx.fail(
                    new DxForbiddenNoAccessException(
                        "AppId not authorised for entity: " + entityId));
                return;
              }
              LOGGER.debug(
                  "CheckItemAccess gRPC response — appId={} entityId={} did={} iid={} accessPolicy={} resourceServer={} policies={}",
                  appId,
                  entityId,
                  did,
                  response.getIid(),
                  response.getAccessPolicy(),
                  response.getResourceServerJson(),
                  response.getPoliciesJson());
              AppIdItemAccessResult result = AppIdItemAccessResult.fromProto(response);
              // Only cache direct-access results; delegation results are always re-checked
              if (did.isEmpty()) {
                cacheService.put(appId, entityId, result);
              }
              mergeMetadata(ctx, result);
              ctx.next();
            })
        .onFailure(
            err -> {
              LOGGER.error(
                  "gRPC CheckItemAccess failed appId={} entityId={} did={}: {}",
                  appId,
                  entityId,
                  did,
                  err.getMessage());
              ctx.fail(new DxInternalServerErrorException("Item access check unavailable"));
            });
  }

  /**
   * Merges item metadata into {@code ctx.user().principal()} so {@code
   * ItemAccessApplicableFilterHandler*} takes the fast path (sees {@code policies} key).
   */
  private void mergeMetadata(RoutingContext ctx, AppIdItemAccessResult result) {
    JsonObject principal = ctx.user().principal();
    principal.put("iid", result.iid());
    principal.put("accessPolicy", result.accessPolicy());
    try {
      principal.put("resourceServer", new JsonArray(result.resourceServerJson()));
    } catch (Exception e) {
      LOGGER.warn("Could not parse resourceServerJson, using empty array");
      principal.put("resourceServer", new JsonArray());
    }
    try {
      principal.put("policies", new JsonArray(result.policiesJson()));
    } catch (Exception e) {
      LOGGER.warn("Could not parse policiesJson, using empty array");
      principal.put("policies", new JsonArray());
    }
  }
}
