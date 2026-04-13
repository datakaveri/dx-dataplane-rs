package org.cdpg.dx.apiserver;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;
import static org.cdpg.dx.common.config.CorsUtil.allowedOrigins;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.TimeoutHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.openapi.RouterBuilderOptions;
import io.vertx.serviceproxy.HelperUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.authentication.client.JwksResolver;
import org.cdpg.dx.auth.authentication.handler.MultiIssuerJwtAuthHandler;
import org.cdpg.dx.auth.authentication.handler.OptionalMultiIssuerJwtAuthHandler;
import org.cdpg.dx.common.FailureHandler;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.util.BlockingExecutionUtil;

public class PublishedApiServerVerticle extends AbstractVerticle {
  private static final Logger LOGGER = LogManager.getLogger(PublishedApiServerVerticle.class);
  private int port;
  private HttpServer server;
  private Router router;
  private URNGenerator urnGenerator;

  @Override
  public void start() throws Exception {
    port = config().getInteger("httpPort", 8445);
    allowedOrigins = config().getJsonArray("corsAllowedOrigin").getList();
    String urnPrefix = config().getString("urnPrefix", "urn:dx:dataplane:");
    this.urnGenerator = new URNGenerator(urnPrefix);

    // Configure Jackson mappers
    ObjectMapper mapper = DatabindCodec.mapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    /*mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);*/
    DatabindCodec.mapper().setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);

    ObjectMapper prettyMapper = mapper.copy();
    prettyMapper.enable(SerializationFeature.INDENT_OUTPUT);
    DatabindCodec.prettyMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);

    String baseUrl = config().getString("publishedurl", "example.com");
    String supportEmail = config().getString("supportEmail", "support@cdpg.org.in");

    String yamlContent =
        vertx
            .fileSystem()
            .readFileBlocking("docs/openapiforpublish.yaml")
            .toString(StandardCharsets.UTF_8);

    String updatedYaml =
        yamlContent.replace("${HOSTNAME}", baseUrl).replace("${SUPPORT_EMAIL}", supportEmail);

    Path tempFile = Files.createTempFile("openapi3-", ".yaml");
    tempFile.toFile().deleteOnExit();
    vertx
        .fileSystem()
        .writeFileBlocking(tempFile.toAbsolutePath().toString(), Buffer.buffer(updatedYaml));

    Future<RouterBuilder> routerBuilderFuture =
            RouterBuilder.create(vertx, tempFile.toUri().toString());

    BlockingExecutionUtil.initialize(vertx);

    List<ApiController> controllers =
        PublishedControllerFactory.createControllers(vertx, config(), this.urnGenerator);

    routerBuilderFuture
        .onSuccess(
            routerBuilder -> {
              try {
                String keyStorePath = config().getString("keystore");
                String keyStorePassword = config().getString("keystorePassword");

                JwksResolver jwksResolver =
                    new JwksResolver(vertx, config().getJsonObject("issuers"), null);

                // Our single unified handlers
                MultiIssuerJwtAuthHandler authHandler = new MultiIssuerJwtAuthHandler(jwksResolver);
                OptionalMultiIssuerJwtAuthHandler optionalAuthHandler =
                    new OptionalMultiIssuerJwtAuthHandler(jwksResolver);

                LOGGER.debug("Adding platform handlers...");
                int timeout = config().getInteger("timeout", 100000);
                routerBuilder.rootHandler(TimeoutHandler.create(timeout, 408));
                routerBuilder.rootHandler(
                    ctx -> {
                      ctx.request().pause();
                      ctx.next();
                    });

                LOGGER.debug("Registering controllers...");
                RouterBuilderOptions factoryOptions =
                    new RouterBuilderOptions().setMountResponseContentTypeHandler(true);
                routerBuilder.setOptions(factoryOptions);

                // Use unified handlers in OpenAPI security schemes
                routerBuilder.securityHandler("authorization", authHandler);
                routerBuilder.securityHandler("optionalAuth", optionalAuthHandler);

                controllers.forEach(controller -> controller.register(routerBuilder));

                LOGGER.debug("Creating router...");
                router = routerBuilder.createRouter();

                LOGGER.debug("Configuring CORS and error handlers...");
                configureCorsHandler(router);
                putCommonResponseHeaders();
                configureFailureHandler(router);
                configureErrorHandlers(router);

                LOGGER.debug("Starting HTTP server...");
                HttpServerOptions serverOptions = new HttpServerOptions();

                /* Documentation routes */
                router
                    .get(ROUTE_STATIC_SPEC)
                    .produces(APPLICATION_JSON)
                    .handler(ctx -> ctx.response().sendFile(tempFile.toAbsolutePath().toString()));

                router
                    .get(ROUTE_DOC)
                    .produces("text/html")
                    .handler(ctx -> ctx.response().sendFile("docs/apidoc.html"));

                router
                    .get("/health/live")
                    .handler(
                        ctx ->
                            ctx.response()
                                .setStatusCode(200)
                                .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
                                .end("Alive"));
                setServerOptions(serverOptions);
                server = vertx.createHttpServer(serverOptions);
                server
                    .requestHandler(router)
                    .listen(
                        port,
                        http -> {
                          if (http.succeeded()) {
                            printDeployedEndpoints(router);
                            LOGGER.info("published api server deployed on port: {}", port);
                          } else {
                            LOGGER.error(
                                "HTTP server failed to start: {}",
                                http.cause().getMessage(),
                                http.cause());
                          }
                        });
              } catch (Exception e) {
                LOGGER.error(
                    "Error during router creation or server startup: {}", e.getMessage(), e);
              }
            })
        .onFailure(
            failure -> {
              LOGGER.error(
                  "Failed to create RouterBuilder from OpenAPI spec: {}",
                  failure.getMessage(),
                  failure);
            });
  }

  private void configureCorsHandler(Router router) {
    CorsHandler corsHandler;
    if (allowedOrigins.contains("*")) {
      corsHandler = CorsHandler.create("*").allowCredentials(false);
    } else {
      corsHandler = CorsHandler.create();
      for (String origin : allowedOrigins) {
        corsHandler.addOrigin(origin);
      }
      corsHandler.allowCredentials(true);
    }

    corsHandler
        .allowedMethod(HttpMethod.GET)
        .allowedMethod(HttpMethod.POST)
        .allowedMethod(HttpMethod.OPTIONS)
        .allowedMethod(HttpMethod.PUT)
        .allowedMethod(HttpMethod.DELETE)
        .allowedMethod(HttpMethod.PATCH)
        .allowedHeader("Content-Type")
        .allowedHeader("Authorization")
        .allowedHeader("Origin");

    router.route().handler(corsHandler);
  }

  private void putCommonResponseHeaders() {
    router
        .route()
        .handler(
            ctx -> {
              ctx.response()
                  .putHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0")
                  .putHeader("Pragma", "no-cache")
                  .putHeader("Expires", "0")
                  .putHeader("X-Content-Type-Options", "nosniff");
              ctx.next();
            });
  }

  private void configureFailureHandler(Router router) {
    router.route().failureHandler(new FailureHandler(this.urnGenerator));
  }

  private void printDeployedEndpoints(Router router) {
    for (Route route : router.getRoutes()) {
      if (route.getPath() != null) {
        LOGGER.info("Deployed endpoint [{}] {}", route.methods(), route.getPath());
      }
    }
  }

  private void setServerOptions(HttpServerOptions serverOptions) {
    boolean isSsl = config().getBoolean("ssl", false);
    if (isSsl) {
      LOGGER.info("Info: Starting HTTPs server");
      String keystore = config().getString("keystore");
      String keystorePassword = config().getString("keystorePassword");
      serverOptions
          .setSsl(true)
          .setKeyCertOptions(new JksOptions().setPath(keystore).setPassword(keystorePassword));
    } else {
      LOGGER.info("Info: Starting HTTP server");
      serverOptions.setSsl(false);
    }
  }

  @Override
  public void stop() {
    if (server != null) {
      server.close();
    }
  }

  private String loadSpec() throws Exception {
    InputStream classpathStream =
        getClass().getClassLoader().getResourceAsStream("docs/openapiforpublish.yaml");
    if (classpathStream == null) {
      classpathStream = getClass().getClassLoader().getResourceAsStream("openapiforpublish.yaml");
    }
    if (classpathStream != null) {
      try (InputStream in = classpathStream) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    return vertx
        .fileSystem()
        .readFileBlocking("docs/openapiforpublish.yaml")
        .toString(StandardCharsets.UTF_8);
  }

  private void configureErrorHandlers(Router router) {
    router.errorHandler(
        401,
        ctx -> {
          HttpServerResponse response = ctx.response();
          if (response.headWritten()) {
            try {
              response.reset();
            } catch (RuntimeException e) {
              LOGGER.error(
                  "Failed to reset response: {}", HelperUtils.convertStackTrace(e).encode());
            }
            return;
          }
          response
              .setStatusCode(401)
              .putHeader(CONTENT_TYPE, APPLICATION_JSON)
              .end("not implemented");
        });
  }
}
