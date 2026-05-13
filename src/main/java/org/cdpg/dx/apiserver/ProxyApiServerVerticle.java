package org.cdpg.dx.apiserver;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auth.appid.cache.AppIdCacheHolder;
import org.cdpg.dx.auth.appid.client.AppIdVerificationClient;
import org.cdpg.dx.auth.appid.handler.AppIdAuthHandler;
import org.cdpg.dx.auth.appid.lookup.GrpcAppCredentialLookup;
import org.cdpg.dx.auth.v2.factory.AuthHandlersV2;
import org.cdpg.dx.auth.v2.handler.AuthenticationHandlerV2;
import org.cdpg.dx.auth.v2.handler.AuthorizationHandler;
import org.cdpg.dx.auth.v2.lookup.UserLookup;
import org.cdpg.dx.auth.v2.model.DxRole;
import org.cdpg.dx.auth.v2.model.UserSnapshot;
import org.cdpg.dx.auth.v2.registry.InMemoryRoleScopeRegistry;
import org.cdpg.dx.auth.v2.resolver.AppCredentialsResolver;
import org.cdpg.dx.auth.v2.resolver.DelegationResolver;
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

    AuthHandlersV2 authV2 = new AuthHandlersV2(new AuthorizationHandler(new InMemoryRoleScopeRegistry()));

    LOGGER.info("AppId gRPC client configured (proxy): {}:{}", host, port);
    return ControllerFactoryProxy.createControllers(
        vertx, config, urnGenerator, appIdItemAccessHandler, authV2);
  }

  @Override
  protected AppIdAuthHandler getAppIdAuthHandler() {
    return new AppIdAuthHandler(AppIdCacheHolder.getAppIdCache(), appIdClient);
  }

  @Override
  protected AuthenticationHandlerV2 getAuthV2Handler() {
    DelegationResolver stubDelegationResolver =
        new DelegationResolver(
            (delegatorSub, delegateeSub) ->
                Future.failedFuture("Delegation via header not yet supported in dataplane"),
            (sub) -> Future.failedFuture("User lookup not yet supported in dataplane"));
    UserLookup passThroughUserLookup =
        sub ->
            Future.succeededFuture(
                Optional.of(new UserSnapshot(sub, null, Set.of(DxRole.values()), false)));
    return new AuthenticationHandlerV2(
        jwksResolver,
        stubDelegationResolver,
        new AppCredentialsResolver(new GrpcAppCredentialLookup(appIdClient), passThroughUserLookup));
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
