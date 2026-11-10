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
  private static final String API_HITS_FIELD = "apiHits";
  private static final String DATA_USAGE_FIELD = "dataUsage";
  private final String hitsKeyPrefix;
  private final RedisService redisService;

  public RedisAccessLimitHandler(RedisService redisService, String redisKeyPrefix) {
    this.redisService = redisService;
    this.hitsKeyPrefix = redisKeyPrefix;
  }

  @Override
  public void handle(RoutingContext context) {
    User user = context.user();
    if (user == null) {
      LOGGER.debug("RedisAccessLimitHandler skipped: user not present in context");
      context.next();
      return;
    }

    String assetId = RoutingContextHelper.getId(context);
    if (assetId == null || assetId.isBlank()) {
      LOGGER.debug("RedisAccessLimitHandler skipped: assetId missing in context");
      context.next();
      return;
    }

    String userId = user.subject();
    JsonObject itemMetaData = RoutingContextHelper.getItemMetaData(context);
    JsonObject accessSource = itemMetaData;
    if (accessSource == null || accessSource.isEmpty()) {
      LOGGER.warn("RedisAccessLimitHandler skipped: itemMetaData missing in context");
      context.next();
      return;
    }

    JsonObject accessEntry = findAccessEntry(accessSource);
    long expiryEpochSeconds = accessEntry != null ? accessEntry.getLong("expiry", -1L) : -1L;

    JsonObject limits =
        accessEntry != null
            ? accessEntry.getJsonObject("limits", new JsonObject())
            : new JsonObject();
    String accessPolicy = accessSource.getString("accessPolicy", "");
    if (isOpenPolicy(accessPolicy)) {
      LOGGER.debug(
          "RedisAccessLimitHandler skipped for open/public policy. userId={}, assetId={}",
          userId,
          assetId);
      context.next();
      return;
    }

    String policyId = RoutingContextHelper.getPolicyId(context);
    if (policyId == null || policyId.isBlank()) {
      context.fail(
          new DxForbiddenNoAccessException("policyId is mandatory for restricted resource"));
      return;
    }

    boolean isOpenPolicy = isOpenPolicy(accessPolicy);
    long apiHitsLimit = limits.getLong(API_HITS_FIELD, -1L);
    long dataUsageLimitBytes = parseDataUsageToBytes(limits.getString(DATA_USAGE_FIELD));
    boolean enforceApiHits = !isOpenPolicy && apiHitsLimit >= 0;
    boolean enforceDataUsage = !isOpenPolicy && dataUsageLimitBytes >= 0;
    String hitsKey = buildHitsKey(policyId, userId, assetId);
    String usageKey = buildUsageKey(policyId, userId, assetId);

    LOGGER.info(
        "RateLimit init: userId={}, assetId={}, policy={},  hitsKey={}, usageKey={}",
        userId,
        assetId,
        accessPolicy,
        hitsKey,
        usageKey);

    Future<Void> checks = ensureRedisKeys(hitsKey, usageKey, expiryEpochSeconds);

    if (enforceApiHits) {
      checks =
          checks
              .compose(v -> redisService.getLong(hitsKey))
              .compose(
                  consumedHits -> {
                    LOGGER.info(
                        "RateLimit pre-check apiHits: userId={}, assetId={}, key={}, consumed={}, limit={}",
                        userId,
                        assetId,
                        hitsKey,
                        consumedHits,
                        apiHitsLimit);
                    if (consumedHits >= apiHitsLimit) {
                      return Future.failedFuture(
                          new DxTooManyRequestsException(
                              "API hit limit exceeded for this asset (" + apiHitsLimit + ")"));
                    }
                    return applyExpiry(hitsKey, expiryEpochSeconds);
                  });
    }

    if (enforceDataUsage) {
      checks =
          checks
              .compose(v -> redisService.getLong(usageKey))
              .compose(
                  consumed -> {
                    LOGGER.info(
                        "RateLimit pre-check dataUsage: userId={}, assetId={}, key={}, consumedBytes={}, limitBytes={}",
                        userId,
                        assetId,
                        usageKey,
                        consumed,
                        dataUsageLimitBytes);
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
              LOGGER.info(
                  "RateLimit checks passed: userId={}, assetId={}, enforceApiHits={}, enforceDataUsage={}",
                  userId,
                  assetId,
                  enforceApiHits,
                  enforceDataUsage);
              registerSuccessUpdateHandler(
                  context,
                  hitsKey,
                  usageKey,
                  apiHitsLimit,
                  dataUsageLimitBytes,
                  enforceApiHits,
                  enforceDataUsage,
                  expiryEpochSeconds,
                  userId,
                  assetId);
              context.next();
            })
        .onFailure(
            err -> {
              LOGGER.warn(
                  "RateLimit checks failed: userId={}, assetId={}, error={}",
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
      boolean enforceApiHits,
      boolean enforceDataUsage,
      long expiryEpochSeconds,
      String userId,
      String assetId) {
    context.addBodyEndHandler(
        v -> {
          int statusCode = context.response().getStatusCode();
          if (statusCode != 200 && statusCode != 201 && statusCode != 204) {
            LOGGER.debug(
                "RateLimit post-update skipped due to status: userId={}, assetId={}, status={}",
                userId,
                assetId,
                statusCode);
            return;
          }

          if (enforceApiHits) {
            redisService
                .incrementByIfWithinLimit(hitsKey, 1L, apiHitsLimit, expiryEpochSeconds)
                .onSuccess(
                    incremented -> {
                      LOGGER.info(
                          "RateLimit apiHits update: userId={}, assetId={}, key={}, delta=1, enforced=true, applied={}",
                          userId,
                          assetId,
                          hitsKey,
                          incremented);
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
          } else {
            redisService
                .incrementBy(hitsKey, 1L)
                .compose(total -> applyExpiry(hitsKey, expiryEpochSeconds))
                .onSuccess(
                    ignored ->
                        LOGGER.debug(
                            "RateLimit apiHits update: userId={}, assetId={}, key={}, delta=1, enforced=false, applied=true",
                            userId,
                            assetId,
                            hitsKey))
                .onFailure(
                    err ->
                        LOGGER.error(
                            "Failed to update non-limited apiHits in Redis for user {} and asset {}: {}",
                            userId,
                            assetId,
                            err.getMessage()));
          }

          Long auditedResponseSize = RoutingContextHelper.getResponseSize(context);
          long bytesWritten =
              (auditedResponseSize != null && auditedResponseSize > 0)
                  ? auditedResponseSize
                  : context.response().bytesWritten();
          LOGGER.debug("RateLimit dataUsage source: responseBytesWritten={}", bytesWritten);
          if (bytesWritten <= 0) {
            LOGGER.debug(
                "RateLimit dataUsage update skipped: userId={}, assetId={}, chosenBytes={}",
                userId,
                assetId,
                bytesWritten);
            return;
          }

          if (enforceDataUsage) {
            redisService
                .incrementByIfWithinLimit(
                    usageKey, bytesWritten, dataUsageLimitBytes, expiryEpochSeconds)
                .onSuccess(
                    incremented -> {
                      LOGGER.info(
                          "RateLimit dataUsage update: userId={}, assetId={}, key={}, deltaBytes={}, enforced=true, applied={}",
                          userId,
                          assetId,
                          usageKey,
                          bytesWritten,
                          incremented);
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
          } else {
            redisService
                .incrementBy(usageKey, bytesWritten)
                .compose(total -> applyExpiry(usageKey, expiryEpochSeconds))
                .onSuccess(
                    ignored ->
                        LOGGER.info(
                            "RateLimit dataUsage update: userId={}, assetId={}, key={}, bytes={}, enforced=false, applied=true",
                            userId,
                            assetId,
                            usageKey,
                            bytesWritten))
                .onFailure(
                    err ->
                        LOGGER.error(
                            "Failed to update non-limited data usage in Redis for user {} and asset {}: {}",
                            userId,
                            assetId,
                            err.getMessage()));
          }
        });
  }

  private Future<Void> ensureRedisKeys(String hitsKey, String usageKey, long expiryEpochSeconds) {
    return redisService
        .incrementBy(hitsKey, 0L)
        .compose(v -> applyExpiry(hitsKey, expiryEpochSeconds))
        .compose(v -> redisService.incrementBy(usageKey, 0L))
        .compose(v -> applyExpiry(usageKey, expiryEpochSeconds))
        .onSuccess(v -> LOGGER.info("RateLimit keys insertion success"))
        .onFailure(err -> LOGGER.error("RateLimit ensure keys failed: {}", err.getMessage()))
        .mapEmpty();
  }

  private Future<Void> applyExpiry(String key, long expiryEpochSeconds) {
    if (expiryEpochSeconds <= 0) {
      return Future.succeededFuture();
    }
    return redisService.expireAt(key, expiryEpochSeconds);
  }

  private boolean isOpenPolicy(String accessPolicy) {
    return accessPolicy != null
        && ("open".equalsIgnoreCase(accessPolicy) || "public".equalsIgnoreCase(accessPolicy));
  }

  private String buildHitsKey(String policyId, String userId, String assetId) {
    return hitsKeyPrefix + ":" + policyId + ":" + userId + ":" + assetId + ":hits";
  }

  private String buildUsageKey(String policyId, String userId, String assetId) {
    return hitsKeyPrefix + ":" + policyId + ":" + userId + ":" + assetId + ":usageBytes";
  }

  private JsonObject findAccessEntry(JsonObject principal) {
    JsonArray accessArray = principal.getJsonArray("access");
    if (accessArray == null) {
      JsonObject cons = getCons(principal);
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
}
