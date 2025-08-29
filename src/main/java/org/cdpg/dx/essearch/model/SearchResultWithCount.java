package org.cdpg.dx.essearch.model;

import java.util.List;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;

public class SearchResultWithCount {
  private final List<ElasticsearchResponse> results;
  private final int totalCount;

  public SearchResultWithCount(List<ElasticsearchResponse> results, int totalCount) {
    this.results = results;
    this.totalCount = totalCount;
  }

  public List<ElasticsearchResponse> getResults() {
    return results;
  }

  public int getTotalCount() {
    return totalCount;
  }
}


