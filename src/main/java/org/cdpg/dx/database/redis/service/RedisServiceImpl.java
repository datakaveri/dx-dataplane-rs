package org.cdpg.dx.database.redis.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.*;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RedisServiceImpl implements RedisService {
  private static final Logger LOGGER = LogManager.getLogger(RedisServiceImpl.class);
  private final RedisAPI redisAPI;

  public RedisServiceImpl(Redis redis) {
    this.redisAPI = RedisAPI.api(redis);
  }

  @Override
  public Future<JsonObject> getJson(String key) {
    Promise<JsonObject> promise = Promise.promise();
    redisAPI
        .send(Command.JSON_GET, key)
        .onSuccess(result -> handleGetResult(result, promise, key))
        .onFailure(
            err -> {
              LOGGER.error("Failed to get from Redis for key {}: {}", key, err.getMessage());
              promise.fail("Failed to get key from Redis: " + err);
            });

    return promise.future();
  }

  private void handleGetResult(Response result, Promise<JsonObject> promise, String key) {
    if (result == null || result.toBuffer().length() == 0) {
      LOGGER.warn("Key does not exist in Redis: {}", key);
      promise.complete(new JsonObject());
    } else {
      LOGGER.info("Result from Redis for key {}: {}", key, result.getClass());
      promise.complete(new JsonObject().put("array", result.toBuffer().toJsonArray()));
    }
  }

  @Override
  public Future<JsonObject> insertJson(String key, JsonObject jsonValue) {
    Promise<JsonObject> promise = Promise.promise();
    List<String> args = buildJsonSetArgs(key, jsonValue);

    redisAPI
        .jsonSet(args)
        .onSuccess(
            res -> {
              LOGGER.info("Successfully inserted JSON value in Redis for key: {}", key);
              promise.complete(new JsonObject().put("status", "success"));
            })
        .onFailure(
            err -> {
              LOGGER.error(
                  "Failed to insert JSON value in Redis for key {}: {}", key, err.getMessage());
              promise.fail("Failed to set key in Redis: " + err);
            });

    return promise.future();
  }

  @Override
  public Future<Long> getLong(String key) {
    Promise<Long> promise = Promise.promise();
    redisAPI
        .send(Command.GET, key)
        .onSuccess(
            response -> {
              if (response == null) {
                promise.complete(0L);
                return;
              }
              try {
                promise.complete(Long.parseLong(response.toString()));
              } catch (NumberFormatException e) {
                promise.fail("Invalid numeric value for key " + key + ": " + response);
              }
            })
        .onFailure(
            err -> {
              LOGGER.error(
                  "Failed to get long value from Redis for key {}: {}", key, err.getMessage());
              promise.fail("Failed to get key from Redis: " + err);
            });
    return promise.future();
  }

  @Override
  public Future<Long> incrementBy(String key, long delta) {
    Promise<Long> promise = Promise.promise();
    redisAPI
        .send(Command.INCRBY, key, String.valueOf(delta))
        .onSuccess(
            response -> {
              if (response == null) {
                promise.fail("Null response for INCRBY key " + key);
                return;
              }
              try {
                promise.complete(Long.parseLong(response.toString()));
              } catch (NumberFormatException e) {
                promise.fail("Invalid INCRBY response for key " + key + ": " + response);
              }
            })
        .onFailure(
            err -> {
              LOGGER.error(
                  "Failed to increment value in Redis for key {}: {}", key, err.getMessage());
              promise.fail("Failed to increment key in Redis: " + err);
            });
    return promise.future();
  }

  @Override
  public Future<Void> expireAt(String key, long epochSeconds) {
    Promise<Void> promise = Promise.promise();
    redisAPI
        .send(Command.EXPIREAT, key, String.valueOf(epochSeconds))
        .onSuccess(response -> promise.complete())
        .onFailure(
            err -> {
              LOGGER.error(
                  "Failed to set key expiry in Redis for key {}: {}", key, err.getMessage());
              promise.fail("Failed to set expiry on Redis key: " + err);
            });
    return promise.future();
  }

  @Override
  public Future<Boolean> incrementByIfWithinLimit(
      String key, long delta, long maxAllowed, long expiryEpochSeconds) {
    Promise<Boolean> promise = Promise.promise();
    String script =
        "local key = KEYS[1] "
            + "local delta = tonumber(ARGV[1]) "
            + "local maxAllowed = tonumber(ARGV[2]) "
            + "local expiry = tonumber(ARGV[3]) "
            + "local current = tonumber(redis.call('GET', key) or '0') "
            + "local nextVal = current + delta "
            + "if nextVal > maxAllowed then "
            + "  redis.call('SET', key, maxAllowed) "
            + "  if expiry > 0 then redis.call('EXPIREAT', key, expiry) end "
            + "  return 0 "
            + "end "
            + "redis.call('INCRBY', key, delta) "
            + "if expiry > 0 then redis.call('EXPIREAT', key, expiry) end "
            + "return 1";

    redisAPI
        .send(
            Command.EVAL,
            script,
            "1",
            key,
            String.valueOf(delta),
            String.valueOf(maxAllowed),
            String.valueOf(expiryEpochSeconds))
        .onSuccess(
            response -> {
              if (response == null) {
                promise.fail("Null response for EVAL key " + key);
                return;
              }
              String result = response.toString();
              promise.complete("1".equals(result));
            })
        .onFailure(
            err -> {
              LOGGER.error(
                  "Failed atomic increment/limit check in Redis for key {}: {}",
                  key,
                  err.getMessage());
              promise.fail("Failed atomic increment with limit in Redis: " + err);
            });

    return promise.future();
  }

  private List<String> buildJsonSetArgs(String key, JsonObject jsonValue) {
    return Arrays.asList(key, ".", jsonValue.encode());
  }
}
