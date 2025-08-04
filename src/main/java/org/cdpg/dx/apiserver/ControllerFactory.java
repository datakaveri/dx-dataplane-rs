package org.cdpg.dx.apiserver;

import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.*;
import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.REDIS_SERVICE_ADDRESS;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.catalogue.service.CatalogueService;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.database.postgres.service.PostgresService;
import org.cdpg.dx.database.redis.service.RedisService;
import org.cdpg.dx.rs.authorization.handler.ResourcePolicyAuthorizationHandler;
import org.cdpg.dx.rs.latest.controller.LatestController;
import org.cdpg.dx.rs.latest.factory.LatestControllerFactory;
import org.cdpg.dx.uniqueattribute.service.UniqueAttributeService;

public class ControllerFactory {
  private static final Logger LOGGER = LogManager.getLogger(ControllerFactory.class);

  private ControllerFactory() {}

  public static List<ApiController> createControllers(Vertx vertx, JsonObject config) {
    final RedisService redisService = RedisService.createProxy(vertx, REDIS_SERVICE_ADDRESS);
    PostgresService pgService = PostgresService.createProxy(vertx, POSTGRES_SERVICE_ADDRESS);
    final UniqueAttributeService uniqueAttrService =
            UniqueAttributeService.createProxy(vertx, UNIQUE_ATTRIBUTE_SERVICE_ADDRESS);
    final CatalogueService catService =
            CatalogueService.createProxy(vertx, CATALOGUE_SERVICE_ADDRESS);
    final ResourcePolicyAuthorizationHandler policyAuthHandler =
            new ResourcePolicyAuthorizationHandler(catService);
    ElasticsearchService elasticsearchService = ElasticsearchService.createProxy(vertx, ELASTIC_SERVICE_ADDRESS);

    String tenantPrefix = config.getString("tenantPrefix");
    String timeLimit = config.getString("timeLimit");
    //DataBrokerService dataBrokerService =
    //    DataBrokerService.createProxy(vertx, DATA_BROKER_SERVICE_ADDRESS);

   // AuditingHandler auditingHandler = new AuditingHandler(dataBrokerService);
    //KeycloakUserService keycloakUserService = new KeycloakUserServiceImpl(config);
    //ElasticsearchService esService =
    //    ElasticsearchService.createProxy(vertx, ELASTIC_SERVICE_ADDRESS);

    ApiController latestController = LatestControllerFactory.create(uniqueAttrService,policyAuthHandler,tenantPrefix,elasticsearchService, timeLimit);
    // TODO create other controllers

    return List.of(latestController);
  }
}
