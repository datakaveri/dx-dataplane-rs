package org.cdpg.dx.essearch.service;

import io.vertx.core.Future;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.essearch.model.SearchQuery;

import java.util.List;


public interface SearchService {
    Future<List<ElasticsearchResponse>> search(SearchQuery searchQuery, String index);
}
