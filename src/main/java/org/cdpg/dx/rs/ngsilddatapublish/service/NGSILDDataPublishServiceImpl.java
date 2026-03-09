package org.cdpg.dx.rs.ngsilddatapublish.service;

import static org.cdpg.dx.databroker.util.Constants.ID;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.databroker.model.ExchangeSubscribersResponse;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.databroker.util.Vhosts;
import org.cdpg.dx.rs.indexgenerator.IndexNameCreation;

public class NGSILDDataPublishServiceImpl implements NGSILDDataPublishService {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDDataPublishServiceImpl.class);

  private final DataBrokerService dataBrokerService;
  private final ElasticsearchService elasticsearchService;

  public NGSILDDataPublishServiceImpl(
      DataBrokerService dataBrokerService, ElasticsearchService elasticsearchService) {
    this.dataBrokerService = dataBrokerService;
    this.elasticsearchService = elasticsearchService;
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

  @Override
  public Future<String> publishDataOnSeek(JsonArray pushedData, String id) {
    for (int i = 0; i < pushedData.size(); i++) {
      JsonObject jsonObject = pushedData.getJsonObject(i);
      jsonObject.remove("entities");
      jsonObject.put(ID, id);
    }
    // LOGGER.trace("Final payload: {}", pushedData.encodePrettily());

    return dataBrokerService
        .publishMessageExternal(id, id, pushedData)
        .onSuccess(
            v -> {
              LOGGER.info("Data published successfully using on seek for id: {}", id);
            })
        .onFailure(
            err -> {
              LOGGER.error("Failed to published data for id: {}. Error: {}", id, err.getMessage());
            });
  }

  @Override
  public Future<String> publishDataOnSeekIntoElastic(JsonArray pushedData, String id) {
    String index = IndexNameCreation.createIndex(id);
    List<QueryModel> docs = new ArrayList<>();
    for (int i = 0; i < pushedData.size(); i++) {
      JsonObject jsonObject = pushedData.getJsonObject(i);
      jsonObject.remove("entities");
      jsonObject.put(ID, id);
      QueryModel model = new QueryModel();
      model.createQueryModelFromDocument(jsonObject);
      docs.add(model);
    }
    return elasticsearchService
        .createDocumentsAutoId(index, docs)
        .compose(
            ids -> {
              LOGGER.info(
                  "Data indexed successfully using on seek for id: {}. Count: {}", id, ids.size());
              return dataBrokerService
                  .listExchange(id, Vhosts.IUDX_PROD)
                  .compose(
                      exchangeSubs ->
                          publishToAllQueuesExceptDatabase(exchangeSubs, id, pushedData));
            })
        .onFailure(
            err -> {
              LOGGER.error(
                  "Failed to index/publish data using on seek for id: {}. Error: {}",
                  id,
                  err.getMessage());
            });
  }

  private Future<String> publishToAllQueuesExceptDatabase(
      ExchangeSubscribersResponse exchangeSubs, String id, JsonArray pushedData) {
    if (exchangeSubs == null || exchangeSubs.getSubscribers() == null) {
      LOGGER.info("RMQ publish skipped for id {}: no subscribers", id);
      return Future.succeededFuture("no-subscribers");
    }
    List<Future<?>> publishes = new ArrayList<>();
    int[] publishTargets = {0};
    exchangeSubs
        .getSubscribers()
        .forEach(
            (queue, routingKeys) -> {
              if ("database".equalsIgnoreCase(queue)) {
                return;
              }
              if (routingKeys == null || routingKeys.isEmpty()) {
                return;
              }
              for (String rk : routingKeys) {
                publishes.add(dataBrokerService.publishMessageExternal(id, rk, pushedData));
                publishTargets[0]++;
              }
            });
    if (publishes.isEmpty()) {
      LOGGER.info("RMQ publish skipped for id {}: no target queues", id);
      return Future.succeededFuture("no-target-queues");
    }
    return Future.join(publishes)
        .map(
            ignored -> {
              LOGGER.info(
                  "RMQ published for id {}. Total publish targets: {}", id, publishTargets[0]);
              return "published";
            });
  }
}
