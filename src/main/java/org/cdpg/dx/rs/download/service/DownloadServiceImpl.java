package org.cdpg.dx.rs.download.service;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.essearch.model.OrderBy;
import org.cdpg.dx.essearch.model.QueryDecoder;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;
import org.cdpg.dx.rs.download.model.GetRequestModel;
import org.cdpg.dx.rs.download.util.CsvScrollStreamHelper;
import org.cdpg.dx.rs.indexgenerator.IndexNameCreation;

public class DownloadServiceImpl implements DownloadService {
  private static final Logger LOGGER = LogManager.getLogger(DownloadServiceImpl.class);
  private final String timeLimit;

  // Add reference to ElasticsearchService for scroll streaming
  private final ElasticsearchService elasticsearchService;

  public DownloadServiceImpl(String timeLimit, ElasticsearchService elasticsearch) {
    this.timeLimit = timeLimit;
    this.elasticsearchService = elasticsearch;
  }

  @Override
  public Future<ReadStream<Buffer>> streamElasticDataCsvScroll(GetRequestModel getRequestModel) {
    String index = IndexNameCreation.createIndex(getRequestModel.id());
    QueryModel queryModel;
    if (getRequestModel.attrFilter()) {
      queryModel =
          new QueryDecoder().getQueryForAttr(getRequestModel.size(), getRequestModel.size());
    } else if (getRequestModel.timeRel() == null || getRequestModel.timeRel().isEmpty()) {
      queryModel =
          new QueryDecoder()
              .getQueryBasedOnObservationDateTime(
                  getRequestModel.size(),
                  getRequestModel.page(),
                  getRequestModel.sortBy(),
                  getRequestModel.sortOrder());
    } else {
      TemporalQueryRequestModel temporal =
          new TemporalQueryRequestModel(
              getRequestModel.timeRel(),
              getRequestModel.time(),
              getRequestModel.endTime(),
              timeLimit,
              getRequestModel.size(),
              getRequestModel.page());
      queryModel =
          new QueryDecoder()
              .getTemporalQueryBasedOnObservationDateTime(
                  temporal, getRequestModel.sortBy(), getRequestModel.sortOrder());
    }

    // Cast to ElasticsearchServiceImpl to access the REST client methods
    if (elasticsearchService instanceof ElasticsearchService) {
      return CsvScrollStreamHelper.streamCsvScroll(
          (ElasticsearchService) elasticsearchService, index, queryModel);
    } else {
      return Future.failedFuture("ElasticsearchService is not an instance of ElasticsearchService");
    }
  }

  @Override
  public Future<ReadStream<Buffer>> streamElasticDataCsvScroll(SearchQuery searchQuery, String id) {
    String index = IndexNameCreation.createIndex(id);
    QueryDecoder decoder = new QueryDecoder();
    QueryModel queryModel = decoder.postSearchQueryModel(searchQuery);
    if (searchQuery.getSort() != null && !searchQuery.getSort().isEmpty()) {
      Map<String, String> sortFields =
          searchQuery.getSort().stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      OrderBy::getColumn, sort -> sort.getDirection().toString()));
      queryModel.setSortFields(sortFields);
    }

    // Cast to ElasticsearchServiceImpl to access the REST client methods
    if (elasticsearchService instanceof ElasticsearchService) {
      return CsvScrollStreamHelper.streamCsvScroll(
          (ElasticsearchService) elasticsearchService, index, queryModel);
    } else {
      return Future.failedFuture("ElasticsearchService is not an instance of ElasticsearchService");
    }
  }
}
