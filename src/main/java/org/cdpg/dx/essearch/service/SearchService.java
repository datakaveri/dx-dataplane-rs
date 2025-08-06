package org.cdpg.dx.essearch.service;

import io.vertx.core.Future;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.essearch.model.TemporalQueryRequestModel;

import java.util.List;


public interface SearchService {
    Future<List<ElasticsearchResponse>> search(SearchQuery searchQuery, String index);
    Future<List<ElasticsearchResponse>> searchTemporalData(String index, TemporalQueryRequestModel temporalQueryRequestModel);
    Future<List<ElasticsearchResponse>> searchAllData(String index, int size, int page);
}
