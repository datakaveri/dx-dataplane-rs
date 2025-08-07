package org.cdpg.dx.essearch.service;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.util.List;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;

public interface SearchService {
  Future<List<ElasticsearchResponse>> search(SearchQuery searchQuery, String index);

  Future<List<ElasticsearchResponse>> searchTemporalData(
      String index, TemporalQueryRequestModel temporalQueryRequestModel);

  Future<List<ElasticsearchResponse>> searchAllData(String index, int size, int page);

  Future<ReadStream<Buffer>> streamAllData(String index, int size, int page);

  Future<ReadStream<Buffer>> streamTemporalData(
      String index, TemporalQueryRequestModel temporalQueryRequestModel);
    Future<ReadStream<Buffer>> streamPostData(
            SearchQuery searchQuery, String index);
}
