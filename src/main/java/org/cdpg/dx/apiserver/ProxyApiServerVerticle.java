package org.cdpg.dx.apiserver;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auth.appid.cache.AppIdCacheHolder;
import org.cdpg.dx.auth.appid.client.AppIdVerificationClient;
import org.cdpg.dx.auth.appid.handler.AppIdAuthHandler;
import org.cdpg.dx.auth.authentication.handler.AuthenticationHandler;
import org.cdpg.dx.auth.authentication.resolver.GrpcAppCredentialsResolver;
import org.cdpg.dx.auth.authentication.resolver.GrpcDelegationResolver;
import org.cdpg.dx.auth.authentication.resolver.JwtResolverImpl;
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
    String host = config.getString("controlplaneHost");
    int port = config.getInteger("controlplaneGrpcPort");

    this.appIdClient = new AppIdVerificationClient(host, port);
    AppIdItemAccessHandler appIdItemAccessHandler =
        new AppIdItemAccessHandler(AppIdCacheHolder.getItemAccessCache(), appIdClient);

    LOGGER.info("AppId gRPC client configured (proxy): {}:{}", host, port);
    return ControllerFactoryProxy.createControllers(
        vertx, config, urnGenerator, appIdItemAccessHandler);
  }

  @Override
  protected AppIdAuthHandler getAppIdAuthHandler() {
    return new AppIdAuthHandler(AppIdCacheHolder.getAppIdCache(), appIdClient);
  }

  @Override
  protected AuthenticationHandler getAuthV2Handler() {
    return new AuthenticationHandler(
        new JwtResolverImpl(jwksResolver),
        new GrpcDelegationResolver(appIdClient),
        new GrpcAppCredentialsResolver(appIdClient));
  }

  @Override
  protected String getNgsildPathPattern() {
    return "/ngsi-ld/v1.*";
  }

  @Override
  public void stop() {
    super.stop();
    if (appIdClient != null) {
      try {
        appIdClient.shutdown();
      } catch (InterruptedException e) {
        LOGGER.warn(
            "Interrupted while shutting down AppId gRPC client (proxy): {}", e.getMessage());
        Thread.currentThread().interrupt();
      }
    }
  }
}
