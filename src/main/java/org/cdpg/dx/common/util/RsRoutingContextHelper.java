package org.cdpg.dx.common.util;

import io.vertx.ext.web.RoutingContext;
import java.util.Optional;
import org.cdpg.dx.common.model.JwtData;

/**
 * Resource-server-specific routing context helper for methods that depend on RS-specific types (e.g.
 * JwtData).
 */
public class RsRoutingContextHelper {

  private static final String JWT_DATA = "jwtData";

  private RsRoutingContextHelper() {}

  public static void setJwtData(RoutingContext routingContext, JwtData jwtData) {
    routingContext.put(JWT_DATA, jwtData);
  }

  public static Optional<JwtData> getJwtData(RoutingContext routingContext) {
    return Optional.ofNullable(routingContext.get(JWT_DATA));
  }
}
