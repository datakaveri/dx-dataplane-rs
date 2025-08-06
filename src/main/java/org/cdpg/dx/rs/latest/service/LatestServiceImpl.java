package org.cdpg.dx.rs.latest.service;

import io.vertx.core.Future;

import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.essearch.model.QueryDecoder;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.latest.model.GetRequestModel;

/**
 * Default implementation of LatestService that retrieves the latest snapshot or single record for a
 * given resource ID from Redis, considering unique attribute grouping if applicable.
 */
public class LatestServiceImpl implements LatestService {
  private static final Logger LOGGER = LogManager.getLogger(LatestServiceImpl.class);
  private final SearchService searchService;
  private final QueryDecoder queryDecoder = new QueryDecoder();
  private final String tenantPrefix;
  private final String timeLimit;

  public LatestServiceImpl(String tenantPrefix, SearchService searchService, String timeLimit) {
    this.timeLimit = Objects.requireNonNull(timeLimit, "timeLimit must not be null");
    this.tenantPrefix = Objects.requireNonNull(tenantPrefix, "tenantPrefix must not be null");
    this.searchService = searchService;
  }

    @Override
    public Future<ResponseModel> getSearch(GetRequestModel getRequestModel) {
      LOGGER.debug("Received getSearch request: {}", getRequestModel.toString());
      return fetchDataFromElastic(getRequestModel)
          .map(results -> {
            LOGGER.debug("Successfully fetched data for ID: {}", getRequestModel.id());
            return new ResponseModel(results, getRequestModel.size(), getRequestModel.page());
          })
          .onFailure(err -> {
            LOGGER.error("Error fetching data for ID: {}", getRequestModel.id(), err);
          });
    }
    private Future<List<ElasticsearchResponse>> fetchDataFromElastic(GetRequestModel getRequestModel){
    String index = tenantPrefix + "__" + getRequestModel.id();
      if(getRequestModel.timeRel()==null || getRequestModel.timeRel().isEmpty()){
          LOGGER.debug("Fetching All data for ID: {}", getRequestModel.id());
        return searchService.searchAllData(index, getRequestModel.size(), getRequestModel.page());
    }else{
      LOGGER.debug("Fetching Temporal data for ID: {}", getRequestModel.id());
          return searchService.searchTemporalData(index, new TemporalQueryRequestModel(getRequestModel.timeRel(), getRequestModel.time(),getRequestModel.endTime(),timeLimit,getRequestModel.size(),getRequestModel.page()));
      }
    }

    @Override
    public Future<ResponseModel> postSearch(SearchQuery searchQuery, String id) {
        String index = tenantPrefix + "__" + id;
        return searchService
                .search(searchQuery, index)
                .map(results -> {
                    LOGGER.debug("Search execution successful for ID: {}", id);
                    return new ResponseModel(results, searchQuery.getSize(), searchQuery.getPage());
                })
                .onFailure(
                        err -> {
                            LOGGER.error("Search execution failed for ID: {} - {}", id, err.getMessage());
                        });
    }

}
  /* @Override
  public Future<ReadStream<Buffer>> streamDataCsvBatched(
      String rsId, int size, int page, String time, String endTime, String timeRel) {
    Objects.requireNonNull(rsId, "Resource ID must not be null");
    String index = tenantPrefix + "__" + rsId;
    TemporalQueryRequestModel temporalQueryRequestModel =
        new TemporalQueryRequestModel(timeRel, time, endTime, timeLimit, size, page);
    QueryModel queryModel =
        queryDecoder.getTemporalQueryBasedOnObservationDateTime(temporalQueryRequestModel);
    queryModel.setSortFields(Map.of("observationDateTime", "desc"));
    return org.cdpg.dx.rs.latest.util.CsvPaginatedStreamHelper.streamCsvPaginated(
        elasticsearchService, index, queryModel, size, page);
  }*/

  /* @Override
  public Future<ReadStream<Buffer>> streamDataCsvBatched(String rsId, int size, int page) {
    Objects.requireNonNull(rsId, "Resource ID must not be null");
    String index = tenantPrefix + "__" + rsId;
    QueryModel queryModel = queryDecoder.getQueryBasedOnObservationDateTime(size, page);
    queryModel.setSortFields(Map.of("observationDateTime", "desc"));
    return org.cdpg.dx.rs.latest.util.CsvPaginatedStreamHelper.streamCsvPaginated(
        elasticsearchService, index, queryModel, size, page);
  }*/

