package org.cdpg.dx.auth.appid;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.impl.AuthenticationHandlerInternal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.authentication.handler.MultiIssuerJwtAuthHandler;

/**
 * Single auth handler that dispatches on header type:
 *   Authorization: Basic ...  → AppId gRPC flow
 *   Authorization: Bearer ... → JWT flow
 *
 * Registered as the "authorization" security scheme so no ChainAuthHandler is needed.
 */
public class CombinedAuthHandler implements AuthenticationHandlerInternal {

  private static final Logger LOGGER = LogManager.getLogger(CombinedAuthHandler.class);

  private final AppIdAuthHandler appIdHandler;
  private final MultiIssuerJwtAuthHandler jwtHandler;

  public CombinedAuthHandler(AppIdAuthHandler appIdHandler, MultiIssuerJwtAuthHandler jwtHandler) {
    this.appIdHandler = appIdHandler;
    this.jwtHandler = jwtHandler;
  }

  @Override
  public void handle(RoutingContext ctx) {
    authenticate(ctx, res -> {
      if (res.succeeded()) {
        ctx.setUser(res.result());
        postAuthentication(ctx);
      } else {
        ctx.fail(res.cause());
      }
    });
  }

  @Override
  public void authenticate(RoutingContext ctx, Handler<AsyncResult<User>> handler) {
    String authHeader = ctx.request().getHeader("Authorization");
    LOGGER.debug("CombinedAuthHandler: authHeader={}", authHeader);
    if (authHeader != null && authHeader.startsWith("Basic ")) {
      appIdHandler.authenticate(ctx, handler);
    } else {
      jwtHandler.authenticate(ctx, handler);
    }
  }

  @Override
  public void postAuthentication(RoutingContext ctx) {
    String principalAppId = ctx.user().principal().getString(AppIdAuthHandler.PRINCIPAL_APP_ID_KEY);
    if (principalAppId != null) {
      appIdHandler.postAuthentication(ctx);
    } else {
      ctx.next();
    }
  }
}
