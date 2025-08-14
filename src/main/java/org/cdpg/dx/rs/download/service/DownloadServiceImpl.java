package org.cdpg.dx.rs.download.service;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.download.model.GetRequestModel;
import org.cdpg.dx.rs.indexgenerator.IndexNameCreation;

public class DownloadServiceImpl implements DownloadService {
  private static final Logger LOGGER = LogManager.getLogger(DownloadServiceImpl.class);
  private final SearchService searchService;
  private final String timeLimit;

  public DownloadServiceImpl(SearchService searchService, String timeLimit) {
    this.searchService = searchService;

    this.timeLimit = timeLimit;
  }

  @Override
  public Future<ReadStream<Buffer>> streamElasticDataCsvBatched(GetRequestModel getRequestModel) {
    String index = IndexNameCreation.createIndex(getRequestModel.id());

    if (getRequestModel.timeRel() == null || getRequestModel.timeRel().isEmpty()) {
      LOGGER.debug("Streaming All data for ID: {}", getRequestModel.id());
      return searchService.streamAllData(index, getRequestModel.size(), getRequestModel.page());
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
              getRequestModel.page()));
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
}
