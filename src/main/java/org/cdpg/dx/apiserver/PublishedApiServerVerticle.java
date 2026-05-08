package org.cdpg.dx.apiserver;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auth.appid.cache.AppIdCacheHolder;
import org.cdpg.dx.auth.appid.client.AppIdVerificationClient;
import org.cdpg.dx.auth.appid.handler.AppIdAuthHandler;
import org.cdpg.dx.common.URNGenerator;

public class PublishedApiServerVerticle extends AbstractApiServerVerticle {
  private static final Logger LOGGER = LogManager.getLogger(PublishedApiServerVerticle.class);
  private AppIdVerificationClient appIdClient;

  @Override
  protected String getOpenApiSpecPath(JsonObject jsonObject) {
    return "docs/openapiforpublish.yaml";
  }

  @Override
  protected int getDefaultPort() {
    return 8445;
  }

  @Override
  protected String getDefaultUrnPrefix() {
    return "urn:dx:dataplanePublish:";
  }

  @Override
  protected String getBaseUrlConfigKey() {
    return "publishedurl";
  }

  @Override
  protected long getDefaultTimeoutMs() {
    return 600_000L;
  }

  @Override
  protected List<ApiController> createControllers(
      Vertx vertx, JsonObject config, URNGenerator urnGenerator) {
    String host = config.getString("controlplaneHost");
    int port = config.getInteger("controlplaneGrpcPort");

    this.appIdClient = new AppIdVerificationClient(host, port);
    AppIdItemAccessHandler appIdItemAccessHandler =
        new AppIdItemAccessHandler(AppIdCacheHolder.getItemAccessCache(), appIdClient);

    LOGGER.info("AppId gRPC client configured: {}:{}", host, port);
    return PublishedControllerFactory.createControllers(vertx, config, urnGenerator);
  }

  @Override
  protected AppIdAuthHandler getAppIdAuthHandler() {
    return new AppIdAuthHandler(AppIdCacheHolder.getAppIdCache(), appIdClient);
  }

  @Override
  public void stop() {
    super.stop();
    if (appIdClient != null) {
      try {
        appIdClient.shutdown();
      } catch (InterruptedException e) {
        LOGGER.warn("Interrupted while shutting down AppId gRPC client: {}", e.getMessage());
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  protected RouterBuilder configureRootHandlerBuilder(
      RouterBuilder routerBuilder, BodyHandler jsonBodyHandler) {
    routerBuilder.rootHandler(
        ctx -> {
          ctx.request().pause();
          ctx.next();
        });
    return routerBuilder;
  }
}
