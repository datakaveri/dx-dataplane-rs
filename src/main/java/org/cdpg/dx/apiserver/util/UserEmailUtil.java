package org.cdpg.dx.apiserver.util;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.keycloak.config.KeycloakConstants;

/**
 * Adds the authenticated user's {@code email} claim to the query sent downstream over RMQ.
 *
 * <p>Best-effort by design: the key is added only when a usable email is present. A missing,
 * blank or non-string claim, an unauthenticated context, or any unexpected error leaves the
 * query untouched — enrichment never fails the request. Only the JWT path carries the claim;
 * tokens issued for appId/secret credentials have no {@code email} in the principal.
 */
public final class UserEmailUtil {
  public static final String EMAIL = "email";
  private static final Logger LOGGER = LogManager.getLogger(UserEmailUtil.class);

  private UserEmailUtil() {}

  public static void addEmailIfEnabled(RoutingContext ctx, JsonObject jsonQuery, boolean enabled) {
    if (!enabled || jsonQuery == null) {
      return;
    }
    String email = extractEmail(ctx);
    if (email == null || email.isBlank()) {
      LOGGER.debug("Email enrichment enabled but no usable email claim on the token, skipping");
      return;
    }
    jsonQuery.put(EMAIL, email);
  }

  private static String extractEmail(RoutingContext ctx) {
    try {
      if (ctx == null || ctx.user() == null || ctx.user().principal() == null) {
        return null;
      }
      Object email = ctx.user().principal().getValue(KeycloakConstants.CLAIM_EMAIL);
      return email instanceof String ? ((String) email).trim() : null;
    } catch (RuntimeException e) {
      LOGGER.warn("Could not read email claim, continuing without it: {}", e.getMessage());
      return null;
    }
  }
}
