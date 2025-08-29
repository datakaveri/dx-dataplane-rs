package org.cdpg.dx.rs.latest.service;

import io.vertx.core.Future;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.essearch.model.SearchQuery;
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
              return new ResponseModel(results, getRequestModel.size(), getRequestModel.page());
            })
        .onFailure(
            err -> {
              LOGGER.error("Error fetching data for ID: {}", getRequestModel.id(), err);
            });
  }

  private Future<List<ElasticsearchResponse>> fetchDataFromElastic(
      GetRequestModel getRequestModel) {
    String index = IndexNameCreation.createIndex(getRequestModel.id());
    if (getRequestModel.timeRel() == null || getRequestModel.timeRel().isEmpty()) {
      LOGGER.debug("Fetching All data for ID: {}", getRequestModel.id());
      return searchService.searchAllData(index, getRequestModel.size(), getRequestModel.page(),
          getRequestModel.sortBy(), getRequestModel.sortOrder());
    } else {
      LOGGER.debug("Fetching Temporal data for ID: {}", getRequestModel.id());
      return searchService.searchTemporalData(
          index,
          new TemporalQueryRequestModel(
              getRequestModel.timeRel(),
              getRequestModel.time(),
              getRequestModel.endTime(),
              timeLimit,
              getRequestModel.size(),
              getRequestModel.page()), getRequestModel.sortBy(), getRequestModel.sortOrder());
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
                  searchResultWithCount.getResults(), searchQuery.getSize(), searchQuery.getPage());
            })
        .onFailure(
            err -> {
              LOGGER.error("Search execution failed for ID: {} - {}", id, err.getMessage());
            });
  }
}
