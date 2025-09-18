package org.cdpg.dx.rs.download.service;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.download.model.GetRequestModel;
import org.cdpg.dx.rs.download.util.CsvPaginatedStreamHelper;
import org.cdpg.dx.rs.indexgenerator.IndexNameCreation;

public class DownloadServiceImpl implements DownloadService {
  private static final Logger LOGGER = LogManager.getLogger(DownloadServiceImpl.class);
  private final SearchService searchService;
  private final String timeLimit;

  // Add reference to ElasticsearchService for scroll streaming
  private final ElasticsearchService elasticsearchService;

  public DownloadServiceImpl(SearchService searchService, String timeLimit) {
    this.searchService = searchService;
    this.timeLimit = timeLimit;
    if (searchService instanceof org.cdpg.dx.essearch.service.SearchServiceImpl) {
      this.elasticsearchService = ((org.cdpg.dx.essearch.service.SearchServiceImpl) searchService).getElasticsearchService();
    } else {
      throw new IllegalArgumentException("SearchService must be SearchServiceImpl for download scroll streaming");
    }
  }

  @Override
  public Future<ReadStream<Buffer>> streamElasticDataCsvBatched(GetRequestModel getRequestModel) {
    String index = IndexNameCreation.createIndex(getRequestModel.id());

    if (getRequestModel.timeRel() == null || getRequestModel.timeRel().isEmpty()) {
      LOGGER.debug("Streaming All data for ID: {}", getRequestModel.id());
      return searchService.streamAllData(
          index,
          getRequestModel.size(),
          getRequestModel.page(),
          getRequestModel.sortBy(),
          getRequestModel.sortOrder());
    } else {
      LOGGER.debug("Streaming Temporal data for ID: {}", getRequestModel.id());
      return searchService.streamTemporalData(
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
  public Future<ReadStream<Buffer>> streamElasticDataCsvBatched(
      SearchQuery searchQuery, String id) {
    String index = IndexNameCreation.createIndex(id);
    return searchService
        .streamPostData(searchQuery, index)
        .onFailure(err -> LOGGER.error("Error:: {}", err.getMessage()));
  }

  @Override
  public Future<ReadStream<Buffer>> streamElasticDataCsvScroll(GetRequestModel getRequestModel) {
    String index = IndexNameCreation.createIndex(getRequestModel.id());
    QueryModel queryModel;
    if (getRequestModel.timeRel() == null || getRequestModel.timeRel().isEmpty()) {
      queryModel = new QueryModel();
      queryModel.setQueryType(org.cdpg.dx.database.elastic.util.QueryType.MATCH_ALL);
    } else {
      TemporalQueryRequestModel temporal = new TemporalQueryRequestModel(
        getRequestModel.timeRel(),
        getRequestModel.time(),
        getRequestModel.endTime(),
        timeLimit,
        getRequestModel.size(),
        getRequestModel.page()
      );
      queryModel = new org.cdpg.dx.essearch.model.QueryDecoder().getTemporalQueryBasedOnObservationDateTime(temporal, getRequestModel.sortBy(), getRequestModel.sortOrder());
    }
    // Always use paginated streaming
    return org.cdpg.dx.rs.download.util.CsvPaginatedStreamHelper.streamCsvPaginated(elasticsearchService, index, queryModel, getRequestModel.size(), getRequestModel.page());
  }

  @Override
  public Future<ReadStream<Buffer>> streamElasticDataCsvScroll(SearchQuery searchQuery, String id) {
    String index = IndexNameCreation.createIndex(id);
    org.cdpg.dx.essearch.model.QueryDecoder decoder = new org.cdpg.dx.essearch.model.QueryDecoder();
    QueryModel queryModel = decoder.postSearchQueryModel(searchQuery);
    if (searchQuery.getSort() != null && !searchQuery.getSort().isEmpty()) {
      java.util.Map<String, String> sortFields =
        searchQuery.getSort().stream()
          .collect(java.util.stream.Collectors.toMap(
            org.cdpg.dx.essearch.model.OrderBy::getColumn,
            sort -> sort.getDirection().toString()
          ));
      queryModel.setSortFields(sortFields);
    }
    // Always use paginated streaming
    return org.cdpg.dx.rs.download.util.CsvPaginatedStreamHelper.streamCsvPaginated(elasticsearchService, index, queryModel, searchQuery.getSize(), searchQuery.getPage());
  }
}
