package org.cdpg.dx.apiserver;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;

import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import java.util.List;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.cdpg.dx.common.URNGenerator;

public class PublishedApiServerVerticle extends AbstractApiServerVerticle {
  @Override
  protected String getOpenApiSpecPath(JsonObject config) {
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
    return 1000000; // 1000s — higher timeout for publish
  }

  @Override
  protected List<ApiController> createControllers(
      Vertx vertx, JsonObject config, URNGenerator urnGenerator) {
    return PublishedControllerFactory.createControllers(vertx, config(), urnGenerator);
  }
  @Override
  protected void configureAdditionalRoutes(Router router, JsonObject config) {
    FileUploadController fileUploadController = new FileUploadController();

    router.post("/ngsi-ld/v1/upload")
            .handler(BodyHandler.create().setBodyLimit(Long.MAX_VALUE))
            .handler(fileUploadController::handleUpload);

    router.delete("/ngsi-ld/v1/upload/:fileId")
            .handler(fileUploadController::handleDelete);
  }
  @Override
  protected long getBodyLimit() {
    return -1; // unlimited body size for data ingestion
  }
}
