package org.cdpg.dx.essearch.model;

import io.vertx.core.json.JsonObject;
import java.util.List;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;

public class SearchResultWithCount {
  private final List<ElasticsearchResponse> results;
  private final int totalCount;
  private final JsonObject aggregations;

  public SearchResultWithCount(List<ElasticsearchResponse> results, int totalCount) {
    this(results, totalCount, null);
  }

  public SearchResultWithCount(
      List<ElasticsearchResponse> results, int totalCount, JsonObject aggregations) {
    this.results = results;
    this.totalCount = totalCount;
    this.aggregations = aggregations;
  }

  public List<ElasticsearchResponse> getResults() {
    return results;
  }

  public int getTotalCount() {
    return totalCount;
  }

  public JsonObject getAggregations() {
    return aggregations;
  }
}


