package org.cdpg.dx.rs.ngsilddatapublish.service;

import static org.cdpg.dx.databroker.util.Constants.ID;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  private static final String ES_DOC_ID_FIELD = "_docId";

  private DataBrokerService dataBrokerService;
  private ElasticsearchService elasticsearchService;

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
    List<QueryModel> documentModels = new ArrayList<>();
    for (int i = 0; i < pushedData.size(); i++) {
      JsonObject jsonObject = pushedData.getJsonObject(i);
      jsonObject.remove("entities");
      jsonObject.put(ID, id);
      JsonObject elasticDocument = jsonObject.copy();
      elasticDocument.put(ES_DOC_ID_FIELD, id + ":" + UUID.randomUUID());
      QueryModel documentModel = new QueryModel();
      documentModel.createQueryModelFromDocument(elasticDocument);
      documentModels.add(documentModel);
    }
    LOGGER.trace("Final payload after preprocessing: {}", pushedData.encodePrettily());
    String index = IndexNameCreation.createIndex(id);

    return elasticsearchService
        .createDocuments(index, documentModels)
        .compose(
            v ->
                dataBrokerService
                    .listExchange(id, Vhosts.IUDX_EXTERNAL)
                    .compose(
                        exchangeSubscribers -> {
                          if (isAssetQueueAttached(exchangeSubscribers, id)) {
                            return dataBrokerService
                                .publishMessageExternal(id, id, pushedData)
                                .onSuccess(
                                    ignored ->
                                        LOGGER.info(
                                            "Data inserted in Elastic and published to queue for id: {}",
                                            id));
                          }
                          LOGGER.info(
                              "Data inserted in Elastic for id: {}. No queue named '{}' attached to exchange, skipping publish.",
                              id,
                              id);
                          return Future.succeededFuture("success");
                        }))
        .onFailure(
            err ->
                LOGGER.error(
                    "Failed publish-on-seek flow for id: {}. Error: {}", id, err.getMessage()));
  }

  private boolean isAssetQueueAttached(ExchangeSubscribersResponse exchangeSubscribers, String id) {
    if (exchangeSubscribers == null || exchangeSubscribers.getSubscribers() == null) {
      return false;
    }
    Map<String, List<String>> subscribers = exchangeSubscribers.getSubscribers();
    List<String> routingKeys = subscribers.get(id);
    return routingKeys != null && routingKeys.contains(id);
  }
}
