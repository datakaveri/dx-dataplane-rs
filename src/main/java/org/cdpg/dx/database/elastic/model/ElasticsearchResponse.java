package org.cdpg.dx.database.elastic.model;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonObject;
import java.util.List;

@DataObject(generateConverter = true)
public class ElasticsearchResponse {
  private static JsonObject aggregations;
  private static int totalHits;
  private String docId;
  private JsonObject source;
  private List<Object> sortValues;

  public ElasticsearchResponse() {
    // Default constructor
  }

  public ElasticsearchResponse(JsonObject json) {
    ElasticsearchResponseConverter.fromJson(json, this);
  }

  public ElasticsearchResponse(String docId, JsonObject source) {
    this.docId = docId;
    this.source = source;
  }

  public ElasticsearchResponse(String docId, JsonObject source, List<Object> sortValues) {
    this.docId = docId;
    this.source = source;
    this.sortValues = sortValues;
  }

  public static JsonObject getAggregations() {
    return aggregations;
  }

  public static void setAggregations(JsonObject aggregations) {
    ElasticsearchResponse.aggregations = aggregations;
  }

  public static int getTotalHits() {
    return totalHits;
  }

  public static void setTotalHits(int totalHits) {
    ElasticsearchResponse.totalHits = totalHits;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    ElasticsearchResponseConverter.toJson(this, json);
    return json;
  }

  public String getDocId() {
    return docId;
  }

  public void setDocId(String docId) {
    this.docId = docId;
  }

  // Alias for compatibility with code expecting getId()
  public String getId() {
    return getDocId();
  }

  public JsonObject getSource() {
    return source;
  }

  public void setSource(JsonObject source) {
    this.source = source;
  }

  public List<Object> getSortValues() {
    return sortValues;
  }

  public void setSortValues(List<Object> sortValues) {
    this.sortValues = sortValues;
  }

  @Override
  public String toString() {
    return "ElasticsearchResponse{"
        + "docId='"
        + docId
        + '\''
        + "totalHits='"
        + totalHits
        + '\''
        + ", "
        + "source="
        + source
        + '}';
  }
}
