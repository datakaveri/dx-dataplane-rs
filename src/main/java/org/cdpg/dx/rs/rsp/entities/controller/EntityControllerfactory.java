package org.cdpg.dx.rs.rsp.entities.controller;

import io.vertx.core.json.JsonObject;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.rs.validation.ParamsValidator;

public class EntityControllerfactory {

  static EntitiesController createEntitiesController(
      DataBrokerService dataBrokerService, URNGenerator urnGenerator, JsonObject config) {

    int maxDaysSync = config.getInteger("maxDaysSync", 10);

    int maxDaysAsync = config.getInteger("maxDaysAsync", 365);
    ParamsValidator validator = new ParamsValidator(maxDaysSync, maxDaysAsync);

    return new EntitiesController(dataBrokerService, validator, urnGenerator);
  }
}
