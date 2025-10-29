package org.cdpg.dx.essearch.service;

import static org.cdpg.dx.database.elastic.util.Constants.MAX_SEARCH_RESULT_LIMIT;
import static org.cdpg.dx.essearch.util.Constants.SOURCE_ONLY;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.database.elastic.model.*;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.essearch.model.*;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;

public class SearchServiceImpl implements SearchService {
  private static final Logger LOGGER = LogManager.getLogger(SearchServiceImpl.class);
  private final ElasticsearchService elasticsearchService;
  private final QueryDecoder queryDecoder = new QueryDecoder();

  private final QueryDecoderNew queryDecoderNew = new QueryDecoderNew();

  public SearchServiceImpl(ElasticsearchService elasticsearchService) {
    this.elasticsearchService =
        Objects.requireNonNull(elasticsearchService, "elasticsearchService must not be null");
  }

  @Override
  public Future<SearchResultWithCount> searchTemporalDataWithCountValidation(
      String index,
      TemporalQueryRequestModel temporalQueryRequestModel,
      String sortBy,
      String sortOrder) {

    Promise<SearchResultWithCount> promise = Promise.promise();
    QueryModel queryModel =
        queryDecoder.getTemporalQueryBasedOnObservationDateTime(
            temporalQueryRequestModel, sortBy, sortOrder);

    // First execute count query to get accurate total hits
    elasticsearchService
        .count(index, queryModel)
        .compose(
            count -> {
              LOGGER.info("Count result: {}", count);

              // Check if count exceeds the maximum limit
              if (count > MAX_SEARCH_RESULT_LIMIT) {
                LOGGER.error("Count {} exceeds maximum limit {}", count, MAX_SEARCH_RESULT_LIMIT);
                return Future.failedFuture(
                    new DxBadRequestException(
                        "Payload too large: "
                            + count
                            + " results found. Use filters to get results within limit or use download API. Maximum allowed: "
                            + MAX_SEARCH_RESULT_LIMIT));
              } else if (count == 0) {
                return Future.failedFuture(
                    new DxBadRequestException("No data found for this index"));
              } else {
                return elasticsearchService
                    .search(index, queryModel, SOURCE_ONLY)
                    .map(searchResult -> new SearchResultWithCount(searchResult, count));
              }
            })
        .onSuccess(
            result -> {
              LOGGER.debug("Temporal search with count validation completed successfully");
              ElasticsearchResponse.setTotalHits(result.getTotalCount());
              promise.complete(result);
            })
        .onFailure(
            failure -> {
              LOGGER.error("Error occurred: {}", failure.getMessage(), failure);
              promise.fail(failure);
            });

    return promise.future();
  }

  @Override
  public Future<SearchResultWithCount> searchAllDataWithCountValidation(
      String index, int size, int page, String sortBy, String sortOrder) {
    LOGGER.info("searching all latest data for index: {}", index);
    Promise<SearchResultWithCount> promise = Promise.promise();
    QueryModel queryModel =
        queryDecoder.getQueryBasedOnObservationDateTime(size, page, sortBy, sortOrder);
    elasticsearchService
        .count(index, queryModel)
        .compose(
            count -> {
              LOGGER.debug("Count result: {}", count);
              if (count == 0) {
                return Future.failedFuture(
                    new DxBadRequestException("No data found for this index"));
              } else {
                return elasticsearchService
                    .search(index, queryModel, SOURCE_ONLY)
                    .map(searchResult -> new SearchResultWithCount(searchResult, count));
              }
            })
        .onSuccess(
            result -> {
              LOGGER.debug(
                  "Latest All data search completed successfully with {} results",
                  result.getTotalCount());
              ElasticsearchResponse.setTotalHits(result.getTotalCount());
              promise.complete(result);
            })
        .onFailure(
            failure -> {
              LOGGER.error("Error during searchAllData: {}", failure.getMessage(), failure);
              promise.fail(failure);
            });
    return promise.future();
  }

  @Override
  public Future<SearchResultWithCount> searchWithCountValidation(
      SearchQuery searchQuery, String index) {
    try {
      String searchType = searchQuery.getSearchType();
      LOGGER.info("search type {} in searchWithCountValidation", searchType);
      QueryModel queryModel = queryDecoder.postSearchQueryModel(searchQuery);
      if (searchQuery.getSort() != null && !searchQuery.getSort().isEmpty()) {
        Map<String, String> sortFields =
            searchQuery.getSort().stream()
                .collect(
                    Collectors.toMap(OrderBy::getColumn, sort -> sort.getDirection().toString()));
        queryModel.setSortFields(sortFields);
      }

      // First execute count query to get accurate total hits
      return elasticsearchService
          .count(index, queryModel)
          .compose(
              count -> {
                LOGGER.info("Count query result: {}", count);
                if (count > MAX_SEARCH_RESULT_LIMIT) {
                  LOGGER.error("Count {} exceeds maximum limit {}", count, MAX_SEARCH_RESULT_LIMIT);
                  return Future.failedFuture(
                      new DxBadRequestException(
                          "Payload too large: "
                              + count
                              + " results found. Use filters to get results within limit or use download API. Maximum allowed: "
                              + MAX_SEARCH_RESULT_LIMIT));
                } else if (count == 0) {
                  return Future.failedFuture(
                      new DxBadRequestException("No data found for this index"));
                } else {
                  return elasticsearchService
                      .search(index, queryModel, SOURCE_ONLY)
                      .map(
                          searchResults -> {
                            LOGGER.debug(
                                "Search completed successfully with {} results",
                                searchResults.size());
                            ElasticsearchResponse.setTotalHits(count);
                            return new SearchResultWithCount(searchResults, count);
                          });
                }
              })
          .onFailure(
              failure -> {
                LOGGER.error(
                    "Error during search with count validation: {}", failure.getMessage(), failure);
              });
    } catch (Exception e) {
      LOGGER.error("Error during search with count validation: {}", e.getMessage(), e);
      return Future.failedFuture(new DxBadRequestException("Failed to process search request"));
    }
  }

  @Override
  public Future<SearchResultWithCount> searchAllDataWithCountValidationWithoutSorting(
      String index, int size, int page) {
    LOGGER.info("searching all latest data(attr) for index: {}", index);
    Promise<SearchResultWithCount> promise = Promise.promise();
    QueryModel queryModel = queryDecoder.getQueryForAttr(size, page);
    elasticsearchService
        .count(index, queryModel)
        .compose(
            count -> {
              LOGGER.debug("Count result: {}", count);
              if (count == 0) {
                return Future.failedFuture(
                    new DxBadRequestException("No data found for this index"));
              } else {
                return elasticsearchService
                    .search(index, queryModel, SOURCE_ONLY)
                    .map(searchResult -> new SearchResultWithCount(searchResult, count));
              }
            })
        .onSuccess(
            result -> {
              LOGGER.debug(
                  "Latest All data(attr) search completed successfully with {} results",
                  result.getTotalCount());
              ElasticsearchResponse.setTotalHits(result.getTotalCount());
              promise.complete(result);
            })
        .onFailure(
            failure -> {
              LOGGER.error("Error during searchAllData: {}", failure.getMessage(), failure);
              promise.fail(failure);
            });
    return promise.future();
  }

  @Override
  public Future<SearchResultWithCount> getSearchTemporalEntityDataWithCountValidation(
      String index, NGSILDQueryParams ngsildQueryParams) {
    LOGGER.info("getSearchTemporalEntityDataWithCountValidation for index: {}", index);
    Promise<SearchResultWithCount> promise = Promise.promise();

    QueryModel queryModel = queryDecoderNew.buildGetTemporalEntityDataQuery(ngsildQueryParams);
    elasticsearchService
        .count(index, queryModel)
        .compose(
            count -> {
              LOGGER.debug("Count result for getTemporalEntity: {}", count);
              if (count == 0) {
                return Future.failedFuture(
                    new DxBadRequestException("No data found for this index"));
              } else {
                return elasticsearchService
                    .search(index, queryModel, SOURCE_ONLY)
                    .map(searchResult -> new SearchResultWithCount(searchResult, count));
              }
            })
        .onSuccess(
            result -> {
              LOGGER.debug(
                  "Get temporal entity search completed successfully with {} results",
                  result.getTotalCount());
              ElasticsearchResponse.setTotalHits(result.getTotalCount());
              promise.complete(result);
            })
        .onFailure(
            failure -> {
              LOGGER.error("Error during get temporal entity: {}", failure.getMessage(), failure);
              promise.fail(failure);
            });

    return promise.future();
  }
}
