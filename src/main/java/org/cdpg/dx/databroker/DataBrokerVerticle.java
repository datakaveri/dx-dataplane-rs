package org.cdpg.dx.databroker;

import io.vertx.rabbitmq.RabbitMQClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.appid.cache.AppIdCacheHolder;
import org.cdpg.dx.auth.appid.cache.AppIdCacheService;
import org.cdpg.dx.auth.appid.cache.AppIdItemAccessCacheService;
import org.cdpg.dx.auth.appid.cache.AppIdRevocationConsumer;
import org.cdpg.dx.databroker.verticle.BaseDataBrokerVerticle;

/**
 * Dataplane-RS DataBrokerVerticle — creates AppId caches, registers them in the shared holder, and
 * starts the RMQ revocation consumer.
 *
 * <p>Must be deployed before ApiServerVerticle so the caches are available via AppIdCacheHolder
 * when auth handlers are wired up.
 */
public class DataBrokerVerticle extends BaseDataBrokerVerticle {

  private static final Logger LOGGER = LogManager.getLogger(DataBrokerVerticle.class);

  @Override
  protected void onBrokerReady(
      RabbitMQClient internalClient, RabbitMQClient prodClient, RabbitClient rabbitClient) {

    RabbitClient.publishEx = config().getString("adapterQueryPublishExchange");
    LOGGER.info("DataBrokerVerticle: RS broker ready, publishEx={}", RabbitClient.publishEx);

    int maxSize = config().getInteger("appIdCacheMaxSize", 1000);
    long ttlMinutes = config().getLong("appIdCacheTtlMinutes", 5L);
    String appIdRevokeExchange = config().getString("appIdRevokeExchange", "revoked-appid");
    String appIdRevokeQueue = config().getString("appIdRevokeQueue", "revoked-appid");

    AppIdCacheService appIdCache = new AppIdCacheService(maxSize, ttlMinutes);
    AppIdItemAccessCacheService itemAccessCache =
        new AppIdItemAccessCacheService(maxSize, ttlMinutes);

    AppIdCacheHolder.register(appIdCache, itemAccessCache);
    LOGGER.info(
        "AppId caches registered in holder (maxSize={}, ttlMinutes={})", maxSize, ttlMinutes);

    new AppIdRevocationConsumer(
            internalClient, appIdCache, itemAccessCache, appIdRevokeExchange, appIdRevokeQueue)
        .start();
  }
}
