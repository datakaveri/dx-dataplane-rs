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
  private final QueryDecoderNew queryDecoderNew;

  public SearchServiceImpl(ElasticsearchService elasticsearchService, String timeLimit) {
    this.elasticsearchService =
        Objects.requireNonNull(elasticsearchService, "elasticsearchService must not be null");
    queryDecoderNew = new QueryDecoderNew(timeLimit);
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
                if ((Integer.parseInt(queryModel.getOffset()))
                        + Integer.parseInt(queryModel.getLimit())
                    > 50000) {
                  return Future.failedFuture(
                      new DxBadRequestException(
                          "Combination of page and size exceeds the maximum limit of 50000"));
                }
                return elasticsearchService
                    .search(index, queryModel, SOURCE_ONLY)
                    .map(
                        searchResult ->
                            new SearchResultWithCount(
                                searchResult.getResults(),
                                count,
                                searchResult.getAggregations()));
              }
            })
        .onSuccess(
            result -> {
              LOGGER.debug("Temporal search with count validation completed successfully");
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
                if ((Integer.parseInt(queryModel.getOffset()))
                        + Integer.parseInt(queryModel.getLimit())
                    > 50000) {
                  return Future.failedFuture(
                      new DxBadRequestException(
                          "Combination of page and size exceeds the maximum limit of 50000"));
                }
                return elasticsearchService
                    .search(index, queryModel, SOURCE_ONLY)
                    .map(
                        searchResult ->
                            new SearchResultWithCount(
                                searchResult.getResults(),
                                count,
                                searchResult.getAggregations()));
              }
            })
        .onSuccess(
            result -> {
              LOGGER.debug(
                  "Latest All data search completed successfully with {} results",
                  result.getTotalCount());
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
                  if (Integer.parseInt(queryModel.getOffset())
                          + Integer.parseInt(queryModel.getLimit())
                      > 50000) {
                    return Future.failedFuture(
                        new DxBadRequestException(
                            "Combination of page and size exceeds the maximum limit of 50000"));
                  }
                  return elasticsearchService
                      .search(index, queryModel, SOURCE_ONLY)
                      .map(
                          searchResult -> {
                            LOGGER.debug(
                                "Search completed successfully with {} results",
                                searchResult.getResults().size());
                            return new SearchResultWithCount(
                                searchResult.getResults(),
                                count,
                                searchResult.getAggregations());
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
                if ((Integer.parseInt(queryModel.getOffset()))
                        + Integer.parseInt(queryModel.getLimit())
                    > 50000) {
                  return Future.failedFuture(
                      new DxBadRequestException(
                          "Combination of page and size exceeds the maximum limit of 50000"));
                }
                return elasticsearchService
                    .search(index, queryModel, SOURCE_ONLY)
                    .map(
                        searchResult ->
                            new SearchResultWithCount(
                                searchResult.getResults(),
                                count,
                                searchResult.getAggregations()));
              }
            })
        .onSuccess(
            result -> {
              LOGGER.debug(
                  "Latest All data(attr) search completed successfully with {} results",
                  result.getTotalCount());
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
              LOGGER.debug("Count for getTemporalEntity: {}", count);
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
                        searchResult ->
                            new SearchResultWithCount(
                                searchResult.getResults(),
                                count,
                                searchResult.getAggregations()));
              }
            })
        .onSuccess(
            result -> {
              LOGGER.debug(
                  "Get temporal entity search completed successfully with {} results",
                  result.getTotalCount());
              promise.complete(result);
            })
        .onFailure(
            failure -> {
              LOGGER.error("Error during get temporal entity: {}", failure.getMessage(), failure);
              promise.fail(failure);
            });

    return promise.future();
  }

  @Override
  public Future<Integer> getSearchTemporalEntityDataOnlyCount(
      String index, NGSILDQueryParams ngsildQueryParams) {
    LOGGER.info("getSearchTemporalEntityDataOnlyCount for index: {}", index);
    Promise<Integer> promise = Promise.promise();
    QueryModel queryModel = queryDecoderNew.buildGetTemporalEntityCountQuery(ngsildQueryParams);
    elasticsearchService
        .count(index, queryModel)
        .onSuccess(
            count -> {
              LOGGER.debug("Count result : {}", count);
              promise.complete(count);
            })
        .onFailure(
            failure -> {
              LOGGER.error(
                  "Error during get temporal entity count: {}", failure.getMessage(), failure);
              promise.fail(failure);
            });
    return promise.future();
  }

  @Override
  public Future<SearchResultWithCount> getSearchEntitiesAttributeDataWithCountValidation(
      String index, NGSILDQueryParams ngsildQueryParams) {
    LOGGER.info("getSearchEntitiesAttributeDataWithCountValidation for index: {}", index);
    Promise<SearchResultWithCount> promise = Promise.promise();

    QueryModel queryModel = queryDecoderNew.buildEntitiesAttributeDataQuery(ngsildQueryParams);
    elasticsearchService
        .count(index, queryModel)
        .compose(
            count -> {
              LOGGER.debug("Count for getEntities: {}", count);
              if (count > MAX_SEARCH_RESULT_LIMIT) {
                LOGGER.error("Count {} exceeds maximum limits {}", count, MAX_SEARCH_RESULT_LIMIT);
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
                        searchResult ->
                            new SearchResultWithCount(
                                searchResult.getResults(),
                                count,
                                searchResult.getAggregations()));
              }
            })
        .onSuccess(
            result -> {
              LOGGER.debug(
                  "Get entities attribute search completed successfully with {} results",
                  result.getTotalCount());
              promise.complete(result);
            })
        .onFailure(
            failure -> {
              LOGGER.error(
                  "Error during get entities attribute: {}", failure.getMessage(), failure);
              promise.fail(failure);
            });

    return promise.future();
  }

  @Override
  public Future<Integer> getSearchEntitiesAttributeDataOnlyCount(
      String index, NGSILDQueryParams ngsildQueryParams) {
    LOGGER.info("getSearchEntitiesAttributeDataOnlyCount for index: {}", index);
    Promise<Integer> promise = Promise.promise();
    QueryModel queryModel = queryDecoderNew.buildEntitiesAttributeCountQuery(ngsildQueryParams);
    elasticsearchService
        .count(index, queryModel)
        .onSuccess(
            count -> {
              LOGGER.debug("Count results : {}", count);
              promise.complete(count);
            })
        .onFailure(
            failure -> {
              LOGGER.error(
                  "Error during get entities attribute count: {}", failure.getMessage(), failure);
              promise.fail(failure);
            });
    return promise.future();
  }
}
