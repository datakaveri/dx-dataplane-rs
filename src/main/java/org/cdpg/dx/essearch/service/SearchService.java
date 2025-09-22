package org.cdpg.dx.essearch.service;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.util.List;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.essearch.model.SearchResultWithCount;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;

public interface SearchService {
  Future<List<ElasticsearchResponse>> search(SearchQuery searchQuery, String index);

  Future<List<ElasticsearchResponse>> searchTemporalData(
      String index,
      TemporalQueryRequestModel temporalQueryRequestModel,
      String sortBy,
      String sortOrder);

  Future<SearchResultWithCount> searchTemporalDataWithCountValidation(
      String index,
      TemporalQueryRequestModel temporalQueryRequestModel,
      String sortBy,
      String sortOrder);

  Future<List<ElasticsearchResponse>> searchAllData(
      String index, int size, int page, String sortBy, String sortOrder);

  Future<SearchResultWithCount> searchAllDataWithCountValidation(
      String index, int size, int page, String sortBy, String sortOrder);

  Future<ReadStream<Buffer>> streamAllData(
      String index, int size, int page, String sortBy, String sortOrder);

  Future<ReadStream<Buffer>> streamTemporalData(
      String index,
      TemporalQueryRequestModel temporalQueryRequestModel,
      String sortBy,
      String sortOrder);

  Future<ReadStream<Buffer>> streamPostData(SearchQuery searchQuery, String index);

  Future<SearchResultWithCount> searchWithCountValidation(SearchQuery searchQuery, String index);
}
