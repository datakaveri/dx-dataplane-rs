package org.cdpg.dx.rs.latest.service;

import io.vertx.core.Future;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.essearch.model.SearchResultWithCount;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.indexgenerator.IndexNameCreation;
import org.cdpg.dx.rs.latest.model.GetRequestModel;

public class LatestServiceImpl implements LatestService {
  private static final Logger LOGGER = LogManager.getLogger(LatestServiceImpl.class);
  private final SearchService searchService;
  private final String timeLimit;

  public LatestServiceImpl(SearchService searchService, String timeLimit) {
    this.timeLimit = Objects.requireNonNull(timeLimit, "timeLimit must not be null");
    this.searchService = searchService;
  }

  @Override
  public Future<ResponseModel> getSearch(GetRequestModel getRequestModel) {
    LOGGER.debug("Received getSearch request: {}", getRequestModel.toString());
    return fetchDataFromElastic(getRequestModel)
        .map(
            results -> {
              LOGGER.debug("Successfully fetched data for ID: {}", getRequestModel.id());
              return new ResponseModel(
                  results.getResults(),
                  getRequestModel.size(),
                  getRequestModel.page(),
                  results.getTotalCount());
            })
        .onFailure(
            err -> {
              LOGGER.error("Error fetching data for ID: {}", getRequestModel.id(), err);
            });
  }

  private Future<SearchResultWithCount> fetchDataFromElastic(GetRequestModel getRequestModel) {
    String index = IndexNameCreation.createIndex(getRequestModel.id());
    LOGGER.debug("Index determined for ID {}: {}", getRequestModel.id(), index);
    if (getRequestModel.attrFilter()) {
      return searchService.searchAllDataWithCountValidationWithoutSorting(
          index, getRequestModel.size(), getRequestModel.page());
    } else if (getRequestModel.timeRel() == null || getRequestModel.timeRel().isEmpty()) {
      return searchService.searchAllDataWithCountValidation(
          index,
          getRequestModel.size(),
          getRequestModel.page(),
          getRequestModel.sortBy(),
          getRequestModel.sortOrder());
    } else {
      return searchService.searchTemporalDataWithCountValidation(
          index,
          new TemporalQueryRequestModel(
              getRequestModel.timeRel(),
              getRequestModel.time(),
              getRequestModel.endTime(),
              timeLimit,
              getRequestModel.size(),
              getRequestModel.page()),
          getRequestModel.sortBy(),
          getRequestModel.sortOrder());
    }
  }

  @Override
  public Future<ResponseModel> postSearch(SearchQuery searchQuery, String id) {
    String index = IndexNameCreation.createIndex(id);
    return searchService
        .searchWithCountValidation(searchQuery, index)
        .map(
            searchResultWithCount -> {
              LOGGER.debug(
                  "Search execution successful for ID: {} with total count: {}",
                  id,
                  searchResultWithCount.getTotalCount());
              return new ResponseModel(
                  searchResultWithCount.getResults(),
                  searchQuery.getSize(),
                  searchQuery.getPage(),
                  searchResultWithCount.getTotalCount());
            })
        .onFailure(
            err -> {
              LOGGER.error("Search execution failed for ID: {} - {}", id, err.getMessage());
            });
  }
}
