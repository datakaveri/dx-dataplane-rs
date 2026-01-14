package org.cdpg.dx.validations.idvalidation;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.util.RoutingContextHelper;

public class IdValidation implements Handler<RoutingContext> {
  private static final Logger LOGGER = LogManager.getLogger(IdValidation.class);

  @Override
  public void handle(RoutingContext event) {
    LOGGER.info("checking id validations");

    String accessPolicy = RoutingContextHelper.getAccessPolicy(event);
    String id = RoutingContextHelper.getId(event);
    String iid = RoutingContextHelper.getIid(event);

    if (accessPolicy.equalsIgnoreCase("Open") || accessPolicy.equalsIgnoreCase("public")) {
      LOGGER.info("open resource, skipping id validation");
      event.next();
    } else {
      if (id.equalsIgnoreCase(iid)) {
        LOGGER.info("id validation successful");
        event.next();
      } else {
        LOGGER.error("id validation failed");
        event.fail(
            new DxBadRequestException("request id does not match with item id, access fail"));
      }
    }
  }
}
