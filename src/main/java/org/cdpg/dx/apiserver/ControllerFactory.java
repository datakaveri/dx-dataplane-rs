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
            searchService, timeLimit, controlPlaneDomain, urnGenerator);
    // TODO create other controllers

    return List.of(latestController, downloadController, onboardingController);
  }
}
