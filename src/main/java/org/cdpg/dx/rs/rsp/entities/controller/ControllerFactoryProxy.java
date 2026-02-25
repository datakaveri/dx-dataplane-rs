package org.cdpg.dx.rs.rsp.entities.controller;

import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.DATA_BROKER_SERVICE_ADDRESS;
import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.REDIS_SERVICE_ADDRESS;
import static org.cdpg.dx.rs.rsp.entities.controller.config.*;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.database.redis.service.RedisService;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.rs.rsp.gateway.controller.GatewayControllerFactory;

public class ControllerFactoryProxy {

  public static List<ApiController> createControllers(
      Vertx vertx, JsonObject config, URNGenerator urnGenerator) {

    DataBrokerService dataBrokerService =
        DataBrokerService.createProxy(vertx, DATA_BROKER_SERVICE_ADDRESS);
    RedisService redisService = RedisService.createProxy(vertx, REDIS_SERVICE_ADDRESS);

    AuditingHandler auditingHandler =
        new AuditingHandler(
            dataBrokerService,
            config.getString("auditingExchange", DEFAULT_AUDITING_EXCHANGE),
            config.getString("auditingRoutingKey", DEFAULT_AUDITING_ROUTING_KEY));

    ApiController entitiesController =
        EntityControllerfactory.createEntitiesController(
            dataBrokerService, urnGenerator, config, auditingHandler, redisService);
    ApiController gatewayController =
        GatewayControllerFactory.createGatewayController(
            dataBrokerService, urnGenerator, config, auditingHandler, redisService);

    return List.of(entitiesController, gatewayController);
  }
}
