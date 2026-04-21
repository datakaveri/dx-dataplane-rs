package org.cdpg.dx.auth.appid;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.AuthenticationHandler;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.appid.cache.AppIdCacheService;
import org.cdpg.dx.auth.appid.client.AppIdVerificationClient;
import org.cdpg.dx.auth.appid.model.AppIdPrincipal;
import org.cdpg.dx.common.exception.DxUnauthorizedException;

/**
 * OpenAPI security handler for AppId/AppSecret authentication (Step 1 — identity only).
 *
 * <p>Verifies credentials via gRPC VerifyAppId, sets a minimal {@code ctx.user()} with
 * {@code sub}, {@code iss}, {@code realm_access.roles} so downstream
 * {@link org.cdpg.dx.auth.authorization.handler.AuthorizationHandler} passes, and stores the
 * {@code appId} string in routing-context data under {@link #APP_ID_KEY} so that the subsequent
 * {@link AppIdItemAccessHandler} can perform the per-entity access check.
 */
public class AppIdAuthHandler implements AuthenticationHandler {

  /** Routing-context key that signals an AppId-authenticated request. */
  public static final String APP_ID_KEY = "appId";

  private static final Logger LOGGER = LogManager.getLogger(AppIdAuthHandler.class);

  private final AppIdCacheService cacheService;
  private final AppIdVerificationClient verificationClient;

  public AppIdAuthHandler(AppIdCacheService cacheService, AppIdVerificationClient verificationClient) {
    this.cacheService = cacheService;
    this.verificationClient = verificationClient;
  }

  @Override
  public void handle(RoutingContext ctx) {
    String authHeader = ctx.request().getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Basic ")) {
      ctx.fail(new DxUnauthorizedException("Missing or invalid Authorization header (expected Basic auth)"));
      return;
    }

    String appId;
    String appSecret;
    try {
      String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
      int colonIdx = decoded.indexOf(':');
      if (colonIdx < 0) {
        ctx.fail(new DxUnauthorizedException("Invalid Basic auth format (expected base64(appId:appSecret))"));
        return;
      }
      appId = decoded.substring(0, colonIdx);
      appSecret = decoded.substring(colonIdx + 1);
    } catch (IllegalArgumentException e) {
      ctx.fail(new DxUnauthorizedException("Invalid Base64 in Authorization header"));
      return;
    }

    if (appId.isBlank() || appSecret.isBlank()) {
      ctx.fail(new DxUnauthorizedException("AppId or AppSecret must not be blank"));
      return;
    }

    cacheService
        .get(appId)
        .ifPresentOrElse(
            principal -> {
              LOGGER.debug("AppId cache hit for appId={}", appId);
              applyPrincipal(ctx, principal);
              ctx.next();
            },
            () -> verifyWithControlplane(ctx, appId, appSecret));
  }

  private void verifyWithControlplane(RoutingContext ctx, String appId, String appSecret) {
    verificationClient
        .verify(appId, appSecret)
        .onSuccess(
            response -> {
              if (!response.getSuccess()) {
                LOGGER.warn("AppId verification failed for appId={}", appId);
                ctx.fail(new DxUnauthorizedException("Invalid AppId credentials"));
                return;
              }
              AppIdPrincipal principal = AppIdPrincipal.fromProto(response.getPrincipal());
              cacheService.put(appId, principal);
              applyPrincipal(ctx, principal);
              ctx.next();
            })
        .onFailure(
            err -> {
              LOGGER.error("gRPC verification error for appId={}: {}", appId, err.getMessage());
              ctx.fail(new DxUnauthorizedException("Authentication service unavailable"));
            });
  }

  /**
   * Sets minimal {@code ctx.user()} (identity fields only) and stores {@code appId} in ctx
   * so {@link AppIdItemAccessHandler} can issue the per-entity access-check gRPC call.
   */
  private void applyPrincipal(RoutingContext ctx, AppIdPrincipal principal) {
    JsonObject userPrincipal =
        new JsonObject()
            .put("sub", principal.ownerId())
            .put("iss", "dx-controlplane")
            .put("realm_access", new JsonObject().put("roles", new JsonArray(principal.roles())));
    ctx.setUser(User.create(userPrincipal));
    ctx.put(APP_ID_KEY, principal.appId());
  }
}
