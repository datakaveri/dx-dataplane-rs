package org.cdpg.dx.rs.latest.service;

import static org.cdpg.dx.database.elastic.util.Constants.SOURCE_ONLY;

import io.vertx.core.Future;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.database.elastic.model.*;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
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
  private final String timeLimit;

  public LatestServiceImpl(
      String tenantPrefix,
      UniqueAttributeService uniqueAttrService,
      ElasticsearchService elasticsearchService, String timeLimit) {
    this.timeLimit = Objects.requireNonNull(timeLimit, "timeLimit must not be null");
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

    return fetchLatestValuesFromElastic(id, size, page, time, endTime, timeRel, timeLimit)
        .onSuccess(
            result -> {
              LOGGER.debug("Successfully fetched latest data for ID: {}", id);
              Future.succeededFuture(result);
            })
        .recover(
            err -> {
              LOGGER.error("Error fetching latest data for ID: {}", id, err);
              return Future.failedFuture(err);
            });
  }

  @Override
  public Future<ResponseModel> getLatestData(String rsId, int size, int page) {
    return fetchLatestValuesFromElastic(rsId, size, page)
        .onSuccess(
            result -> {
              LOGGER.debug("Successfully fetched latest data for ID: {}", rsId);
              Future.succeededFuture(result);
            })
        .recover(
            err -> {
              LOGGER.error("Error fetching latest data for ID: {}", rsId, err);
              return Future.failedFuture(err);
            });
  }

  @Override
  public Future<ResponseModel> postSearch(
      QueryDecoderRequestDTO queryDecoderRequestDTO, String id) {
    String index = tenantPrefix + "__" + id;
    try {
      String searchType = queryDecoderRequestDTO.getSearchType();
      LOGGER.info("search type {}", searchType);
      QueryDecoder queryDecoder = new QueryDecoder();
      QueryModel queryModel = queryDecoder.getQueryModel(queryDecoderRequestDTO);
      if (queryDecoderRequestDTO.getSort() != null && !queryDecoderRequestDTO.getSort().isEmpty()) {
        Map<String, String> sortFields =
            queryDecoderRequestDTO.getSort().stream()
                .collect(
                    Collectors.toMap(OrderBy::getColumn, sort -> sort.getDirection().toString()));
        queryModel.setSortFields(sortFields);
      }
      return elasticsearchService
          .search(index, queryModel, SOURCE_ONLY)
          .map(
              results ->
                  new ResponseModel(
                      results, queryDecoderRequestDTO.getSize(), queryDecoderRequestDTO.getPage()))
          .onFailure(err -> LOGGER.error("Search execution failed: {}", err.getMessage()));
    } catch (Exception e) {
      LOGGER.error("Error during postSearch: {}", e.getMessage(), e);
      return Future.failedFuture(new DxBadRequestException("Failed to process search request"));
    }
  }

  @Override
  public Future<ReadStream<Buffer>> streamDataCsvBatched(String rsId, int size, int page, String time, String endTime, String timeRel) {
    Objects.requireNonNull(rsId, "Resource ID must not be null");
    String index = tenantPrefix + "__" + rsId;
    TemporalQueryRequestModel temporalQueryRequestModel =
        new TemporalQueryRequestModel(timeRel, time, endTime, timeLimit, size, page);
    QueryModel queryModel = queryDecoder.getTemporalQueryBasedOnObservationDateTime(temporalQueryRequestModel);
    queryModel.setSortFields(Map.of("observationDateTime", "desc"));
    return org.cdpg.dx.rs.latest.util.CsvPaginatedStreamHelper.streamCsvPaginated(elasticsearchService, index, queryModel, size, page);
  }

  @Override
  public Future<ReadStream<Buffer>> streamDataCsvBatched(String rsId, int size, int page) {
    Objects.requireNonNull(rsId, "Resource ID must not be null");
    String index = tenantPrefix + "__" + rsId;
    QueryModel queryModel = queryDecoder.getQueryBasedOnObservationDateTime(size, page);
    queryModel.setSortFields(Map.of("observationDateTime", "desc"));
    return org.cdpg.dx.rs.latest.util.CsvPaginatedStreamHelper.streamCsvPaginated(elasticsearchService, index, queryModel, size, page);
  }

  private Future<ResponseModel> fetchLatestValuesFromElastic(
      String id, int size, int page, String time, String endTime, String timeRel, String timeLimit) {
    Objects.requireNonNull(id, "Resource ID must not be null");
    String index = tenantPrefix + "__" + id;

    TemporalQueryRequestModel temporalQueryRequestModel =
        new TemporalQueryRequestModel(timeRel, time, endTime, timeLimit, size, page);
    LOGGER.debug("model request : {}", temporalQueryRequestModel.toString());
    QueryModel queryModel =
        queryDecoder.getTemporalQueryBasedOnObservationDateTime(temporalQueryRequestModel);

    // Use "SOURCE_ONLY" as options to avoid AGGREGATION_ONLY logic and get hits
    return elasticsearchService
        .search(index, queryModel, SOURCE_ONLY)
        .map(
            results -> {
              LOGGER.trace("size of results {}", results.size());
              return new ResponseModel(results, size, page);
            });
  }

  private Future<ResponseModel> fetchLatestValuesFromElastic(String id, int size, int page) {
    Objects.requireNonNull(id, "Resource ID must not be null");
    QueryModel queryModel = queryDecoder.getQueryBasedOnObservationDateTime(size, page);
    String index = tenantPrefix + "__" + id;

    // Use "SOURCE_ONLY" as options to avoid AGGREGATION_ONLY logic and get hits
    return elasticsearchService
        .search(index, queryModel, SOURCE_ONLY)
        .map(
            results -> {
              LOGGER.trace("size of results {}", results.size());
              return new ResponseModel(results, size, page);
            });
  }
}
