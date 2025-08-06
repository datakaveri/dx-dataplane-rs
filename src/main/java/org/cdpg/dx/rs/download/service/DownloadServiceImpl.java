package org.cdpg.dx.rs.download.service;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.download.model.GetRequestModel;

public class DownloadServiceImpl implements DownloadService {
  private static final Logger LOGGER = LogManager.getLogger(DownloadServiceImpl.class);
  private final SearchService searchService;
  private final String tenantPrefix;
  private final String timeLimit;

  public DownloadServiceImpl(SearchService searchService, String tenantPrefix, String timeLimit) {
    this.searchService = searchService;
    this.tenantPrefix = tenantPrefix;
    this.timeLimit = timeLimit;
  }

  @Override
  public Future<ReadStream<Buffer>> streamElasticDataCsvBatched(GetRequestModel getRequestModel) {
    String index = tenantPrefix + "__" + getRequestModel.id();
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
}
