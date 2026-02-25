package org.cdpg.dx.validations.ratelimit;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxForbiddenNoAccessException;
import org.cdpg.dx.common.exception.DxTooManyRequestsException;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.database.redis.service.RedisService;

public class RedisAccessLimitHandler implements Handler<RoutingContext> {
  private static final Logger LOGGER = LogManager.getLogger(RedisAccessLimitHandler.class);
  private static final String HITS_KEY_PREFIX = "dx";
  private static final String API_HITS_FIELD = "apiHits";
  private static final String DATA_USAGE_FIELD = "dataUsage";

  private final RedisService redisService;

  public RedisAccessLimitHandler(RedisService redisService) {
    this.redisService = redisService;
  }

  @Override
  public void handle(RoutingContext context) {
    User user = context.user();
    if (user == null) {
      context.next();
      return;
    }

    String assetId = RoutingContextHelper.getId(context);
    if (assetId == null || assetId.isBlank()) {
      context.next();
      return;
    }

    JsonObject principal = user.principal();
    String userId = user.subject();
    JsonObject itemMetaData = RoutingContextHelper.getItemMetaData(context);
    JsonObject accessSource =
        principal.containsKey("cons")
            ? principal
            : (itemMetaData != null && !itemMetaData.isEmpty() ? itemMetaData : principal);

    JsonObject accessEntry = findAccessEntry(accessSource);
    if (accessEntry == null) {
      context.next();
      return;
    }

    long expiryEpochSeconds = accessEntry.getLong("expiry", -1L);
    if (isExpired(expiryEpochSeconds)) {
      context.fail(new DxForbiddenNoAccessException("Access token has expired"));
      return;
    }

    JsonObject subjects =
        accessSource.getJsonObject(
            "subjects", principal.getJsonObject("subjects", accessEntry.getJsonObject("subjects")));
    if (!isSubjectAllowed(principal, userId, subjects)) {
      context.fail(new DxForbiddenNoAccessException("User is not allowed for this resource"));
      return;
    }

    JsonObject limits = accessEntry.getJsonObject("limits", new JsonObject());
    long apiHitsLimit = limits.getLong(API_HITS_FIELD, -1L);
    long dataUsageLimitBytes = parseDataUsageToBytes(limits.getString(DATA_USAGE_FIELD));
    String hitsKey = buildHitsKey(userId, assetId);
    String usageKey = buildUsageKey(userId, assetId);

    Future<Void> checks = Future.succeededFuture();

    if (apiHitsLimit >= 0) {
      checks =
          checks
              .compose(v -> redisService.getLong(hitsKey))
              .compose(
                  consumedHits -> {
                    if (consumedHits >= apiHitsLimit) {
                      return Future.failedFuture(
                          new DxTooManyRequestsException(
                              "API hit limit exceeded for this asset (" + apiHitsLimit + ")"));
                    }
                    return applyExpiry(hitsKey, expiryEpochSeconds);
                  });
    }

    if (dataUsageLimitBytes >= 0) {
      checks =
          checks
              .compose(v -> redisService.getLong(usageKey))
              .compose(
                  consumed -> {
                    if (consumed >= dataUsageLimitBytes) {
                      return Future.failedFuture(
                          new DxTooManyRequestsException(
                              "Data usage limit exceeded for this asset"));
                    }
                    return applyExpiry(usageKey, expiryEpochSeconds);
                  });
    }

    checks
        .onSuccess(
            v -> {
              registerSuccessUpdateHandler(
                  context,
                  hitsKey,
                  usageKey,
                  apiHitsLimit,
                  dataUsageLimitBytes,
                  expiryEpochSeconds,
                  userId,
                  assetId);
              context.next();
            })
        .onFailure(
            err -> {
              LOGGER.warn(
                  "Quota enforcement failed for user {} and asset {}: {}",
                  userId,
                  assetId,
                  err.getMessage());
              context.fail(err);
            });
  }

  private void registerSuccessUpdateHandler(
      RoutingContext context,
      String hitsKey,
      String usageKey,
      long apiHitsLimit,
      long dataUsageLimitBytes,
      long expiryEpochSeconds,
      String userId,
      String assetId) {
    context.addBodyEndHandler(
        v -> {
          if (context.response().getStatusCode() >= 400) {
            return;
          }

          if (apiHitsLimit >= 0) {
            redisService
                .incrementByIfWithinLimit(hitsKey, 1L, apiHitsLimit, expiryEpochSeconds)
                .onSuccess(
                    incremented -> {
                      if (!incremented) {
                        LOGGER.warn(
                            "API hit quota update skipped at limit boundary. userId={}, assetId={}, limitHits={}",
                            userId,
                            assetId,
                            apiHitsLimit);
                      }
                    })
                .onFailure(
                    err ->
                        LOGGER.error(
                            "Failed to update apiHits in Redis for user {} and asset {}: {}",
                            userId,
                            assetId,
                            err.getMessage()));
          }

          if (dataUsageLimitBytes >= 0) {
            Long auditedResponseSize = RoutingContextHelper.getResponseSize(context);
            long bytesWritten =
                auditedResponseSize != null ? auditedResponseSize : context.response().bytesWritten();
            if (bytesWritten <= 0) {
              return;
            }

            redisService
                .incrementByIfWithinLimit(
                    usageKey, bytesWritten, dataUsageLimitBytes, expiryEpochSeconds)
                .onSuccess(
                    incremented -> {
                      if (!incremented) {
                        LOGGER.warn(
                            "Data usage quota update skipped at limit boundary. userId={}, assetId={}, attemptedBytes={}, limitBytes={}",
                            userId,
                            assetId,
                            bytesWritten,
                            dataUsageLimitBytes);
                      }
                    })
                .onFailure(
                    err ->
                        LOGGER.error(
                            "Failed to update data usage in Redis for user {} and asset {}: {}",
                            userId,
                            assetId,
                            err.getMessage()));
          }
        });
  }

  private Future<Void> applyExpiry(String key, long expiryEpochSeconds) {
    if (expiryEpochSeconds <= 0) {
      return Future.succeededFuture();
    }
    return redisService.expireAt(key, expiryEpochSeconds);
  }

  private boolean isExpired(long expiryEpochSeconds) {
    return expiryEpochSeconds > 0 && expiryEpochSeconds <= (System.currentTimeMillis() / 1000L);
  }

  private String buildHitsKey(String userId, String assetId) {
    return HITS_KEY_PREFIX + ":" + userId + ":" + assetId + ":hits";
  }

  private String buildUsageKey(String userId, String assetId) {
    return HITS_KEY_PREFIX + ":" + userId + ":" + assetId + ":usageBytes";
  }

  private JsonObject findAccessEntry(JsonObject principal) {
    JsonArray accessArray = principal.getJsonArray("access");
    if (accessArray == null) {
      JsonObject cons = principal.getJsonObject("cons");
      if (cons != null) {
        accessArray = cons.getJsonArray("access");
      }
    }
    if (accessArray == null || accessArray.isEmpty()) {
      return null;
    }

    JsonObject fallbackEntry = null;
    for (Object object : accessArray) {
      if (object instanceof JsonObject access) {
        if (fallbackEntry == null) {
          fallbackEntry = access;
        }
        JsonObject limits = access.getJsonObject("limits", new JsonObject());
        if (access.containsKey("subjects")
            || access.containsKey("expiry")
            || limits.containsKey(API_HITS_FIELD)
            || limits.containsKey(DATA_USAGE_FIELD)) {
          return access;
        }
      }
    }
    return fallbackEntry;
  }

  private boolean isSubjectAllowed(JsonObject principal, String userId, JsonObject subjects) {
    if (subjects == null || subjects.isEmpty()) {
      return true;
    }

    JsonArray allowedUserIds = subjects.getJsonArray("allowedUserIds", new JsonArray());
    if (!allowedUserIds.isEmpty() && !allowedUserIds.contains(userId)) {
      return false;
    }

    String orgId = principal.getString("organisation_id", "");
    JsonArray allowedOrgIds = subjects.getJsonArray("allowedOrgIds", new JsonArray());
    if (!allowedOrgIds.isEmpty() && (orgId == null || orgId.isBlank() || !allowedOrgIds.contains(orgId))) {
      return false;
    }

    JsonArray allowedRoles = subjects.getJsonArray("allowedRoles", new JsonArray());
    if (!allowedRoles.isEmpty() && !hasAnyAllowedRole(principal, allowedRoles)) {
      return false;
    }
    return true;
  }

  private boolean hasAnyAllowedRole(JsonObject principal, JsonArray allowedRoles) {
    JsonObject realmAccess = principal.getJsonObject("realm_access", new JsonObject());
    JsonArray tokenRoles = realmAccess.getJsonArray("roles", new JsonArray());
    if (tokenRoles.isEmpty()) {
      return false;
    }
    for (Object roleObj : tokenRoles) {
      if (!(roleObj instanceof String role)) {
        continue;
      }
      if (containsIgnoreCase(allowedRoles, role)) {
        return true;
      }
    }
    return false;
  }

  private boolean containsIgnoreCase(JsonArray array, String value) {
    for (Object obj : array) {
      if (obj instanceof String str && str.equalsIgnoreCase(value)) {
        return true;
      }
    }
    return false;
  }

  private long parseDataUsageToBytes(String dataUsage) {
    if (dataUsage == null || dataUsage.isBlank()) {
      return -1L;
    }
    String[] tokens = dataUsage.split(":");
    if (tokens.length != 2) {
      LOGGER.warn("Invalid dataUsage format: {}", dataUsage);
      return -1L;
    }

    try {
      double value = Double.parseDouble(tokens[0].trim());
      String unit = tokens[1].trim().toLowerCase(Locale.ROOT);
      double multiplier =
          switch (unit) {
            case "b" -> 1D;
            case "kb" -> 1024D;
            case "mb" -> 1024D * 1024D;
            case "gb" -> 1024D * 1024D * 1024D;
            case "tb" -> 1024D * 1024D * 1024D * 1024D;
            default -> -1D;
          };
      if (multiplier < 0) {
        LOGGER.warn("Unsupported dataUsage unit: {}", unit);
        return -1L;
      }
      return (long) (value * multiplier);
    } catch (NumberFormatException e) {
      LOGGER.warn("Invalid dataUsage numeric value: {}", dataUsage);
      return -1L;
    }
  }
}
