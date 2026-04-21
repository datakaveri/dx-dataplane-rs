package org.cdpg.dx.apiserver;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import io.vertx.ext.web.handler.AuthenticationHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.appid.AppIdAuthHandler;
import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auth.appid.cache.AppIdCacheService;
import org.cdpg.dx.auth.appid.cache.AppIdItemAccessCacheService;
import org.cdpg.dx.auth.appid.client.AppIdVerificationClient;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.rs.rsp.entities.controller.ControllerFactoryProxy;

public class ProxyApiServerVerticle extends AbstractApiServerVerticle {

  private static final Logger LOGGER = LogManager.getLogger(ProxyApiServerVerticle.class);

  private AppIdVerificationClient appIdClient;

  @Override
  protected String getOpenApiSpecPath(JsonObject config) {
    return "docs/openapiForGateway.yaml";
  }

  @Override
  protected int getDefaultPort() {
    return 8444;
  }

  @Override
  protected String getDefaultUrnPrefix() {
    return "urn:dx:dataplane:";
  }

  @Override
  protected String getBaseUrlConfigKey() {
    return "gatewayurl";
  }

  @Override
  protected long getDefaultTimeoutMs() {
    return 1000000;
  }

  @Override
  protected List<ApiController> createControllers(
      Vertx vertx, JsonObject config, URNGenerator urnGenerator) {
    String host = config.getString("controlplaneHost", "localhost");
    int port = config.getInteger("controlplaneGrpcPort", 9090);
    int maxSize = config.getInteger("appIdCacheMaxSize", 1000);
    long ttlMinutes = config.getLong("appIdCacheTtlMinutes", 5L);

    this.appIdClient = new AppIdVerificationClient(host, port);
    AppIdItemAccessCacheService itemAccessCache = new AppIdItemAccessCacheService(maxSize, ttlMinutes);
    AppIdItemAccessHandler appIdItemAccessHandler = new AppIdItemAccessHandler(itemAccessCache, appIdClient);

    LOGGER.info("AppId gRPC client configured (proxy): {}:{}", host, port);
    return ControllerFactoryProxy.createControllers(vertx, config, urnGenerator, appIdItemAccessHandler);
  }

  @Override
  protected AuthenticationHandler getAppIdAuthHandler() {
    JsonObject cfg = config();
    int maxSize = cfg.getInteger("appIdCacheMaxSize", 1000);
    long ttlMinutes = cfg.getLong("appIdCacheTtlMinutes", 5L);

    AppIdCacheService cacheService = new AppIdCacheService(maxSize, ttlMinutes);
    return new AppIdAuthHandler(cacheService, appIdClient);
  }

  @Override
  public void stop() {
    super.stop();
    if (appIdClient != null) {
      try {
        appIdClient.shutdown();
      } catch (InterruptedException e) {
        LOGGER.warn("Interrupted while shutting down AppId gRPC client (proxy): {}", e.getMessage());
        Thread.currentThread().interrupt();
      }
    }
  }
}
