package org.cdpg.dx.rs.rsp.gateway.controller;

import io.vertx.core.json.JsonObject;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.rs.rsp.gateway.util.GatewayParamValidator;

public class GatewayControllerFactory {

  public static GatewayController createGatewayController(
      DataBrokerService dataBrokerService,
      URNGenerator urnGenerator,
      JsonObject config,
      AuditingHandler auditingHandler /*,
      RedisService redisService,
      String redisKeyPrefix*/) {

    int maxDaysSync = config.getInteger("maxDaysSync", 365);

    int maxDaysAsync = config.getInteger("maxDaysAsync", 365);
    GatewayParamValidator validator = new GatewayParamValidator(maxDaysSync, maxDaysAsync);

    return new GatewayController(
        dataBrokerService,
        validator,
        urnGenerator,
        config.getString("controlPlaneDomain"),
        auditingHandler /*,
                        redisService,
                        redisKeyPrefix*/);
  }
}
