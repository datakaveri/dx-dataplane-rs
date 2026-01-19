package org.cdpg.dx.rs.ngsilddatapublish.service;

import static org.cdpg.dx.databroker.util.Constants.ID;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.databroker.service.DataBrokerService;

public class NGSILDDataPublishServiceImpl implements NGSILDDataPublishService {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDDataPublishServiceImpl.class);

  private DataBrokerService dataBrokerService;

  public NGSILDDataPublishServiceImpl(DataBrokerService dataBrokerService) {
    this.dataBrokerService = dataBrokerService;
  }

  @Override
  public Future<String> publishData(JsonArray ngsildData, String id) {

    for (int i = 0; i < ngsildData.size(); i++) {
      JsonObject jsonObject = ngsildData.getJsonObject(i);
      jsonObject.remove("entities");
      jsonObject.put(ID, id);
    }
    LOGGER.trace("Final request payload: {}", ngsildData.encodePrettily());

    return dataBrokerService
        .publishMessageExternal(id, id, ngsildData)
        .onSuccess(
            v -> {
              LOGGER.info("Data published successfully for id: {}", id);
            })
        .onFailure(
            err -> {
              LOGGER.error("Failed to publish data for id: {}. Error: {}", id, err.getMessage());
            });
  }
}
