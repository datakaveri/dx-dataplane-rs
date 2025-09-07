package org.cdpg.dx.rs.rsp.entities.controller;

import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.DATA_BROKER_SERVICE_ADDRESS;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.databroker.service.DataBrokerService;

public class ControllerFactoryProxy {

  public static List<ApiController> createControllers(
      Vertx vertx, JsonObject config, URNGenerator urnGenerator) {

    DataBrokerService dataBrokerService =
        DataBrokerService.createProxy(vertx, DATA_BROKER_SERVICE_ADDRESS);

    ApiController entitiesController =
        EntityControllerfactory.createEntitiesController(dataBrokerService, urnGenerator, config);

    return List.of(entitiesController);
  }
}
