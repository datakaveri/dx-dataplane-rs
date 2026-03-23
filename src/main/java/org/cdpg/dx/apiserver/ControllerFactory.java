package org.cdpg.dx.apiserver;

import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.*;
import static org.cdpg.dx.rs.rsp.entities.controller.config.DEFAULT_AUDITING_EXCHANGE;
import static org.cdpg.dx.rs.rsp.entities.controller.config.DEFAULT_AUDITING_ROUTING_KEY;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.essearch.service.SearchServiceImpl;
import org.cdpg.dx.rs.admin.controller.ElasticOnboardingController;
import org.cdpg.dx.rs.admin.service.OnboardingService;
import org.cdpg.dx.rs.admin.service.OnboardingServiceImpl;
import org.cdpg.dx.rs.download.factory.DownloadControllerFactory;
import org.cdpg.dx.rs.indexgenerator.IndexNameCreation;
import org.cdpg.dx.rs.latest.factory.LatestControllerFactory;
import org.cdpg.dx.rs.ngsild.factory.NGSILDControllerFactory;

public class ControllerFactory {
  private static final Logger LOGGER = LogManager.getLogger(ControllerFactory.class);

  private ControllerFactory() {}

  public static List<ApiController> createControllers(
      Vertx vertx, JsonObject config, URNGenerator urnGenerator) {

    ElasticsearchService elasticsearchService =
        ElasticsearchService.createProxy(vertx, ELASTIC_SERVICE_ADDRESS);
    /* RedisService redisService = RedisService.createProxy(vertx, REDIS_SERVICE_ADDRESS);*/
    DataBrokerService dataBrokerService =
        DataBrokerService.createProxy(vertx, DATA_BROKER_SERVICE_ADDRESS);
    String timeLimit = config.getString("timeLimit");
    SearchService searchService = new SearchServiceImpl(elasticsearchService, timeLimit);

    String tenantPrefix = config.getString("tenantPrefix");
    String controlPlaneDomain = config.getString("controlPlaneDomain");
    /*String redisKeyPrefix = config.getString("redisKeyPrefix", "dx");*/
    OnboardingService onboardingService = new OnboardingServiceImpl(elasticsearchService);
    ApiController onboardingController =
        new ElasticOnboardingController(onboardingService, tenantPrefix, urnGenerator);

    IndexNameCreation.tenantPrefixs = tenantPrefix;
    int maxDaysSync = config.getInteger("maxDaysSync", 20);
    int maxDaysAsync = config.getInteger("maxDaysAsync", 365);
    AuditingHandler auditingHandler =
        new AuditingHandler(
            dataBrokerService,
            config.getString("auditingExchange", DEFAULT_AUDITING_EXCHANGE),
            config.getString("auditingRoutingKey", DEFAULT_AUDITING_ROUTING_KEY));
    ApiController latestController =
        LatestControllerFactory.create(
            searchService, timeLimit, controlPlaneDomain, urnGenerator, auditingHandler /*,
            redisService,
            redisKeyPrefix*/);
    ApiController downloadController =
        DownloadControllerFactory.create(
            timeLimit,
            controlPlaneDomain,
            urnGenerator,
            elasticsearchService,
            auditingHandler /*,
                            redisService,
                            redisKeyPrefix*/);

    ApiController ngsildController =
        NGSILDControllerFactory.create(
            searchService,
            controlPlaneDomain,
            urnGenerator,
            maxDaysSync,
            maxDaysAsync,
            auditingHandler /*,
                            redisService,
                            redisKeyPrefix*/);

    /* ApiController ngsildDataPublishController =
    NGSILDDataPublishFactory.create(
        controlPlaneDomain, urnGenerator, auditingHandler, dataBrokerService);*/
    // TODO create other controllers

    return List.of(
        latestController, downloadController, onboardingController, ngsildController /*,
        ngsildDataPublishController*/);
  }
}
