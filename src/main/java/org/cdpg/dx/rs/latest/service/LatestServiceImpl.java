package org.cdpg.dx.rs.latest.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.database.elastic.model.QueryDecoder;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.model.TemporalQueryRequestModel;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.rs.latest.model.LatestData;
import org.cdpg.dx.rs.latest.util.LatestRedisCommandArgsBuilder;
import org.cdpg.dx.uniqueattribute.service.UniqueAttributeService;

/**
 * Default implementation of LatestService that retrieves the latest snapshot or single record for a
 * given resource ID from Redis, considering unique attribute grouping if applicable.
 */
public class LatestServiceImpl implements LatestService {
  private static final Logger LOGGER = LogManager.getLogger(LatestServiceImpl.class);
  /*private final RedisService redisService;*/
  private final LatestRedisCommandArgsBuilder argsBuilder;
  private final UniqueAttributeService uniqueAttrService;
  private final ElasticsearchService elasticsearchService;
  private final QueryDecoder queryDecoder = new QueryDecoder();
  private final String tenantPrefix;

  public LatestServiceImpl(
      /*RedisService redisService,*/
      String tenantPrefix,
      UniqueAttributeService uniqueAttrService,
      ElasticsearchService elasticsearchService) {
    /*this.redisService = Objects.requireNonNull(redisService, "redisService must not be null");*/
    this.tenantPrefix = Objects.requireNonNull(tenantPrefix, "tenantPrefix must not be null");
    this.uniqueAttrService =
        Objects.requireNonNull(uniqueAttrService, "uniqueAttrService must not be null");
    this.elasticsearchService =
        Objects.requireNonNull(elasticsearchService, "elasticsearchService must not be null");
    this.argsBuilder = new LatestRedisCommandArgsBuilder();
  }

  @Override
  public Future<ResponseModel> getLatestData(
      String id, int size, int page, String time, String endTime, String timeRel) {
    Objects.requireNonNull(id, "Resource ID must not be null");

    return fetchLatestValuesFromElastic(id, size, page, time, endTime, timeRel)
        .onSuccess(result->{
            LOGGER.debug("Successfully fetched latest data for ID: {}", id);
            Future.succeededFuture(result);
        }).recover(err-> {
          LOGGER.error("Error fetching latest data for ID: {}", id, err);
          return Future.failedFuture(err);
        });
  }

  @Override
  public Future<ResponseModel> getLatestData(String rsId, int size, int page) {
    return fetchLatestValuesFromElastic(rsId, size, page)
        .onSuccess(result->{
            LOGGER.debug("Successfully fetched latest data for ID: {}", rsId);
            Future.succeededFuture(result);
        })
        .recover(
            err -> {
              LOGGER.error("Error fetching latest data for ID: {}", rsId, err);
              return Future.failedFuture(err);
            });
  }

  private Future<Boolean> isUniqueAttribute(String id) {
    LOGGER.trace("Checking unique attribute existence for ID={}", id);
    return uniqueAttrService
        .fetchUniqueAttributeInfo(id)
        .map(info -> info.containsKey("unique_attribute"));
  }

  /*private Future<JsonArray> fetchLatestValues(String id, boolean groupSnapshot) {
      Objects.requireNonNull(id, "Resource ID must not be null");

      RedisArgs args = argsBuilder.buildRedisArgs(id, groupSnapshot, tenantPrefix);
      LOGGER.trace("Searching Redis with key={}, path={}", args.key(), args.path());

      return redisService
          .searchAsync(args.key(), args.path())
          .map(redisResult -> parseResponse(args.key(), redisResult.toJson(), groupSnapshot));
    }
  */
  private Future<ResponseModel> fetchLatestValuesFromElastic(
      String id, int size, int page, String time, String endTime, String timeRel) {
    Objects.requireNonNull(id, "Resource ID must not be null");
    String index = tenantPrefix + "__" + id;

    TemporalQueryRequestModel temporalQueryRequestModel =
        new TemporalQueryRequestModel(timeRel, time, endTime, time, size, page);
    LOGGER.debug("model request : {}", temporalQueryRequestModel.toString());
    QueryModel queryModel =
        queryDecoder.getTemporalQueryBasedOnObservationDateTime(temporalQueryRequestModel);

    // Use "SOURCE_ONLY" as options to avoid AGGREGATION_ONLY logic and get hits
    return elasticsearchService
        .search(index, queryModel, "SOURCE_ONLY")
        .map(
            results -> {
              LOGGER.error("size of results {}", results.size());
             return new ResponseModel(
                        results, size, page);
            });
  }

  private Future<ResponseModel> fetchLatestValuesFromElastic(
          String id, int size, int page) {
    Objects.requireNonNull(id, "Resource ID must not be null");
    QueryModel queryModel = queryDecoder.getQueryBasedOnObservationDateTime(size,page);
    String index = tenantPrefix + "__" + id;

    // Use "SOURCE_ONLY" as options to avoid AGGREGATION_ONLY logic and get hits
    return elasticsearchService
            .search(index, queryModel, "SOURCE_ONLY")
            .map(
                    results -> {
                        LOGGER.error("size of results {}", results.size());
                        return new ResponseModel(
                                results, size, page);
                    });
  }

  /**
   * Parses the raw Redis JSON result into a JsonArray suitable for LatestData.
   *
   * @param key the Redis key used for grouping removal
   * @param result the raw JsonObject returned by Redis
   * @param grouped whether the query was grouped on unique attribute
   * @return a JsonArray of results
   */
  private JsonArray parseResponse(String key, JsonObject result, boolean grouped) {
    if (grouped) {
      result.remove(key);
      return new JsonArray(
          result.stream().map(java.util.Map.Entry::getValue).collect(Collectors.toList()));
    }
    return new JsonArray().add(result);
  }
}
