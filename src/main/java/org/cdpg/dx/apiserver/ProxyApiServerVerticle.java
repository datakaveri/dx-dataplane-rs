package org.cdpg.dx.apiserver;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.rs.rsp.entities.controller.ControllerFactoryProxy;

public class ProxyApiServerVerticle extends AbstractApiServerVerticle {

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
    return 1000000; // 1000s — higher timeout for gateway proxy
  }

  @Override
  protected List<ApiController> createControllers(
      Vertx vertx, JsonObject config, URNGenerator urnGenerator) {
    // Register both gateway controllers and standard controllers (for side-effects)
    List<ApiController> proxyControllers =
        ControllerFactoryProxy.createControllers(vertx, config, urnGenerator);
    return proxyControllers;
  }
}
