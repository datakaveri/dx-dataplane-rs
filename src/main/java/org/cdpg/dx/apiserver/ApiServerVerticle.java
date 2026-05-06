package org.cdpg.dx.apiserver;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auth.appid.cache.AppIdCacheHolder;
import org.cdpg.dx.auth.appid.client.AppIdVerificationClient;
import org.cdpg.dx.auth.appid.handler.AppIdAuthHandler;
import org.cdpg.dx.common.URNGenerator;

public class ApiServerVerticle extends AbstractApiServerVerticle {

  private static final Logger LOGGER = LogManager.getLogger(ApiServerVerticle.class);

  private AppIdVerificationClient appIdClient;

  @Override
  protected String getOpenApiSpecPath(JsonObject config) {
    return "docs/openapi.yaml";
  }

  @Override
  protected int getDefaultPort() {
    return 8443;
  }

  @Override
  protected String getDefaultUrnPrefix() {
    return "urn:dx:dataplane:";
  }

  @Override
  protected String getBaseUrlConfigKey() {
    return "ngsildurl";
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
    return ControllerFactory.createControllers(vertx, config, urnGenerator, appIdItemAccessHandler);
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
  protected void configureAdditionalRoutes(Router router, JsonObject config) {
    FileUploadController fileUploadController = new FileUploadController();

    router
        .post("/ngsi-ld/v1/upload")
        .handler(BodyHandler.create().setBodyLimit(Long.MAX_VALUE))
        .handler(fileUploadController::handleUpload);

    router.delete("/ngsi-ld/v1/upload/:fileId").handler(fileUploadController::handleDelete);
  }

  @Override
  protected long getBodyLimit() {
    return -1;
  }
}
