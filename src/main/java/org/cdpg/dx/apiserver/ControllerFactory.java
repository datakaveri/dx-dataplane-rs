package org.cdpg.dx.apiserver;

import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.*;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.catalogue.service.CatalogueService;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.database.postgres.service.PostgresService;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.essearch.service.SearchServiceImpl;
import org.cdpg.dx.rs.admin.controller.ElasticOnboardingController;
import org.cdpg.dx.rs.admin.service.OnboardingService;
import org.cdpg.dx.rs.admin.service.OnboardingServiceImpl;
import org.cdpg.dx.rs.authorization.handler.ResourcePolicyAuthorizationHandler;
import org.cdpg.dx.rs.download.factory.DownloadControllerFactory;
import org.cdpg.dx.rs.indexgenerator.IndexNameCreation;
import org.cdpg.dx.rs.latest.factory.LatestControllerFactory;

public class ControllerFactory {
  private static final Logger LOGGER = LogManager.getLogger(ControllerFactory.class);

  private ControllerFactory() {}

  public static List<ApiController> createControllers(
      Vertx vertx, JsonObject config, URNGenerator urnGenerator) {
    PostgresService pgService = PostgresService.createProxy(vertx, POSTGRES_SERVICE_ADDRESS);
    final CatalogueService catService =
        CatalogueService.createProxy(vertx, CATALOGUE_SERVICE_ADDRESS);
    final ResourcePolicyAuthorizationHandler policyAuthHandler =
        new ResourcePolicyAuthorizationHandler(catService);
    ElasticsearchService elasticsearchService =
        ElasticsearchService.createProxy(vertx, ELASTIC_SERVICE_ADDRESS);

    // --- Add: extract real ElasticClient and ElasticsearchServiceImpl config from modules array ---
    String databaseIp = null;
    Integer databasePortObj = null;
    String databaseUser = null;
    String databasePassword = null;
    if (config.containsKey("modules")) {
      for (Object moduleObj : config.getJsonArray("modules")) {
        io.vertx.core.json.JsonObject module = null;
        if (moduleObj instanceof io.vertx.core.json.JsonObject) {
          module = (io.vertx.core.json.JsonObject) moduleObj;
        } else if (moduleObj instanceof java.util.Map) {
          module = new io.vertx.core.json.JsonObject((java.util.Map) moduleObj);
        } else {
          continue;
        }
        LOGGER.info("Found module id: {}", module.getString("id"));
        if ("org.cdpg.dx.database.elastic.ElasticsearchVerticle".equals(module.getString("id"))) {
          LOGGER.info("ElasticsearchVerticle module full contents: {}", module.encodePrettily());
          String dbIPUpper = module.getString("databaseIP");
          String dbIPLower = module.getString("databaseIp");
          LOGGER.info("databaseIP (upper): {}", dbIPUpper);
          LOGGER.info("databaseIp (lower): {}", dbIPLower);
          databaseIp = dbIPUpper;
          if (databaseIp == null) {
            databaseIp = dbIPLower;
          }
          LOGGER.info("databaseIp value used: {}", databaseIp);
          databasePortObj = module.getInteger("databasePort");
          databaseUser = module.getString("databaseUser");
          databasePassword = module.getString("databasePassword");
          break;
        }
      }
    }
    if (databaseIp == null || databasePortObj == null || databaseUser == null || databasePassword == null) {
      LOGGER.error("Could not find one or more Elasticsearch config values in modules. Using fallback values: database.iudx.io, 24034, rs-user, tBoDisz97b012knA2CaN");
      databaseIp = "database.iudx.io";
      databasePortObj = 24034;
      databaseUser = "rs-user";
      databasePassword = "tBoDisz97b012knA2CaN";
    }
    int databasePort = databasePortObj;
    org.cdpg.dx.database.elastic.ElasticClient realElasticClient = new org.cdpg.dx.database.elastic.ElasticClient(databaseIp, databasePort, databaseUser, databasePassword);
    org.cdpg.dx.database.elastic.service.ElasticsearchServiceImpl realElasticsearchService = new org.cdpg.dx.database.elastic.service.ElasticsearchServiceImpl(realElasticClient);
    SearchService downloadSearchService = new SearchServiceImpl(realElasticsearchService);
    // --- End add ---

    SearchService searchService = new SearchServiceImpl(elasticsearchService);

    String tenantPrefix = config.getString("tenantPrefix");
    String timeLimit = config.getString("timeLimit");
    String controlPlaneDomain = config.getString("controlPlaneDomain");
    OnboardingService onboardingService = new OnboardingServiceImpl(elasticsearchService);
    ApiController onboardingController =
        new ElasticOnboardingController(onboardingService, tenantPrefix, urnGenerator);

    IndexNameCreation.tenantPrefixs = tenantPrefix;

    ApiController latestController =
        LatestControllerFactory.create(searchService, timeLimit, controlPlaneDomain, urnGenerator);
    ApiController downloadController =
        DownloadControllerFactory.create(
            downloadSearchService, timeLimit, controlPlaneDomain, urnGenerator);
    // TODO create other controllers

    return List.of(latestController, downloadController, onboardingController);
  }
}
