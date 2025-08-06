package org.cdpg.dx.essearch.service;

import static org.cdpg.dx.essearch.util.Constants.SOURCE_ONLY;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.exception.DxEsException;
import org.cdpg.dx.database.elastic.model.*;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.essearch.model.OrderBy;
import org.cdpg.dx.essearch.model.QueryDecoder;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;
import org.cdpg.dx.rs.download.util.CsvPaginatedStreamHelper;

public class SearchServiceImpl implements SearchService {
  private static final Logger LOGGER = LogManager.getLogger(SearchServiceImpl.class);
  private final ElasticsearchService elasticsearchService;
  private final QueryDecoder queryDecoder = new QueryDecoder();

  public SearchServiceImpl(ElasticsearchService elasticsearchService) {
    this.elasticsearchService =
        Objects.requireNonNull(elasticsearchService, "elasticsearchService must not be null");
  }

  @Override
  public Future<List<ElasticsearchResponse>> searchTemporalData(
      String index, TemporalQueryRequestModel temporalQueryRequestModel) {
    QueryModel queryModel =
        queryDecoder.getTemporalQueryBasedOnObservationDateTime(temporalQueryRequestModel);
    return elasticsearchService
        .search(index, queryModel, SOURCE_ONLY)
        .onSuccess(
            result -> {
              Future.succeededFuture(result);
            })
        .onFailure(
            failure -> {
              LOGGER.error("Error during searchAllData: {}", failure.getMessage(), failure);
              Future.failedFuture(new DxEsException("Failed to process search request"));
            });
  }

  @Override
  public Future<List<ElasticsearchResponse>> searchAllData(String index, int size, int page) {
    LOGGER.info("searching all data for index: {}", index);
    QueryModel queryModel = queryDecoder.getQueryBasedOnObservationDateTime(size, page);
    return elasticsearchService
        .search(index, queryModel, SOURCE_ONLY)
        .onSuccess(
            result -> {
              Future.succeededFuture(result);
            })
        .onFailure(
            failure -> {
              LOGGER.error("Error during searchAllData: {}", failure.getMessage(), failure);
              Future.failedFuture(new DxEsException("Failed to process search request"));
            });
  }

  @Override
  public Future<ReadStream<Buffer>> streamAllData(String index, int size, int page) {
    LOGGER.info("Streaming all data for index: {}", index);
    QueryModel queryModel = queryDecoder.getQueryBasedOnObservationDateTime(size, page);
    return CsvPaginatedStreamHelper.streamCsvPaginated(
        elasticsearchService, index, queryModel, size, page);
  }

  @Override
  public Future<ReadStream<Buffer>> streamTemporalData(
      String index, TemporalQueryRequestModel temporalQueryRequestModel) {
    LOGGER.info("Streaming temporal data for index: {}", index);
    QueryModel queryModel =
        queryDecoder.getTemporalQueryBasedOnObservationDateTime(temporalQueryRequestModel);
    return CsvPaginatedStreamHelper.streamCsvPaginated(
        elasticsearchService,
        index,
        queryModel,
        temporalQueryRequestModel.getSize(),
        temporalQueryRequestModel.getPage());
  }

  @Override
  public Future<List<ElasticsearchResponse>> search(SearchQuery searchQuery, String index) {
    try {
      String searchType = searchQuery.getSearchType();
      LOGGER.info("search type {}", searchType);
      QueryModel queryModel = queryDecoder.postSearchQueryModel(searchQuery);
      if (searchQuery.getSort() != null && !searchQuery.getSort().isEmpty()) {
        Map<String, String> sortFields =
            searchQuery.getSort().stream()
                .collect(
                    Collectors.toMap(OrderBy::getColumn, sort -> sort.getDirection().toString()));
        queryModel.setSortFields(sortFields);
      }
      return elasticsearchService
          .search(index, queryModel, SOURCE_ONLY)
          .onSuccess(
              result -> {
                Future.succeededFuture(result);
              });
    } catch (Exception e) {
      LOGGER.error("Error during postSearch: {}", e.getMessage(), e);
      return Future.failedFuture(new DxBadRequestException("Failed to process search request"));
    }
  }
}
