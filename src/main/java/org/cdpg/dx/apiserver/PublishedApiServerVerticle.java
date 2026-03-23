package org.cdpg.dx.apiserver;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;

import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import java.util.List;
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
}
