package org.cdpg.dx.validations.provider;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxForbiddenException;
import org.cdpg.dx.common.util.RoutingContextHelper;

public class ProviderDelegateValidationHandler implements Handler<RoutingContext> {
  private static final Logger LOGGER =
      LogManager.getLogger(ProviderDelegateValidationHandler.class);

  @Override
  public void handle(RoutingContext event) {
    String providerUserId = event.user().subject();
    String ownerUserId = RoutingContextHelper.getOwnerUserId(event);

    roleValidation(providerUserId, ownerUserId)
        .onSuccess(
            result -> {
              if (result) {
                event.next();
              } else {
                LOGGER.error("Permission not allowed ");
                event.fail(new DxForbiddenException("Permission not allowed for this user"));
              }
            })
        .onFailure(
            fail -> {
              LOGGER.error("Error during role validation: {}", fail.getMessage());
              event.fail(fail);
            });
  }

  Future<Boolean> roleValidation(String providerUserId, String ownerUserId) {
    LOGGER.trace("roleValidation() started");
    Promise<Boolean> promise = Promise.promise();
    try {
      if (providerUserId.equalsIgnoreCase(ownerUserId)) {
        LOGGER.info("success");
        promise.complete(true);
      } else {
        LOGGER.error("Permission failed");
        promise.complete(false);
      }
    } catch (Exception e) {
      LOGGER.error("Error in roleValidation: {}", e.getMessage());
      promise.fail(e);
    }
    return promise.future();
  }
}
