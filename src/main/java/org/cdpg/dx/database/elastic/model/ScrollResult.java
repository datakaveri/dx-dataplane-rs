package org.cdpg.dx.database.elastic.model;

import java.util.List;

public class ScrollResult {
  private final List<ElasticsearchResponse> results;
  private final String scrollId;

  public ScrollResult(List<ElasticsearchResponse> results, String scrollId) {
    this.results = results;
    this.scrollId = scrollId;
  }

  public List<ElasticsearchResponse> getResults() {
    return results;
  }

  public String getScrollId() {
    return scrollId;
  }
}

