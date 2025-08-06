package org.cdpg.dx.essearch.service;

import static org.cdpg.dx.essearch.util.Constants.SOURCE_ONLY;

import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.database.elastic.model.*;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.essearch.model.OrderBy;
import org.cdpg.dx.essearch.model.QueryDecoder;
import org.cdpg.dx.essearch.model.SearchQuery;

/**
 * Default implementation of LatestService that retrieves the latest snapshot or single record for a
 * given resource ID from Redis, considering unique attribute grouping if applicable.
 */
public class SearchServiceImpl implements SearchService {
  private static final Logger LOGGER = LogManager.getLogger(SearchServiceImpl.class);
  private final ElasticsearchService elasticsearchService;
  private final QueryDecoder queryDecoder = new QueryDecoder();

  public SearchServiceImpl(ElasticsearchService elasticsearchService) {
    this.elasticsearchService =
        Objects.requireNonNull(elasticsearchService, "elasticsearchService must not be null");
  }

  @Override
  public Future<List<ElasticsearchResponse>> search(SearchQuery searchQuery, String index) {
    try {
      String searchType = searchQuery.getSearchType();
      LOGGER.info("search type {}", searchType);
      QueryDecoder queryDecoder = new QueryDecoder();
      QueryModel queryModel = queryDecoder.getSearchQueryModel(searchQuery);
      if (searchQuery.getSort() != null && !searchQuery.getSort().isEmpty()) {
        Map<String, String> sortFields =
            searchQuery.getSort().stream()
                .collect(
                    Collectors.toMap(OrderBy::getColumn, sort -> sort.getDirection().toString()));
        queryModel.setSortFields(sortFields);
      }
      return elasticsearchService
          .search(index, queryModel, SOURCE_ONLY)
          .onSuccess(
              result -> {
                Future.succeededFuture(result);
              });
    } catch (Exception e) {
      LOGGER.error("Error during postSearch: {}", e.getMessage(), e);
      return Future.failedFuture(new DxBadRequestException("Failed to process search request"));
    }
  }
}
