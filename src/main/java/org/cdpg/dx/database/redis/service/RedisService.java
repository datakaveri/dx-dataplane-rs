package org.cdpg.dx.database.redis.service;

import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

@VertxGen
@ProxyGen
public interface  RedisService {


    Future<JsonObject> getJson(String key);


    Future<JsonObject> insertJson(String key, JsonObject jsonObject);

    Future<Long> getLong(String key);

    Future<Long> incrementBy(String key, long delta);

    Future<Void> expireAt(String key, long epochSeconds);

    Future<Boolean> incrementByIfWithinLimit(
            String key, long delta, long maxAllowed, long expiryEpochSeconds);

    @GenIgnore
    static RedisService createProxy(Vertx vertx, String address) {
        return new RedisServiceVertxEBProxy(vertx, address);
    }


}
