package org.cdpg.dx.database.elastic.model;

import io.vertx.codegen.annotations.DataObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

@DataObject(generateConverter = true)
public class ScrollResult {
  private final List<ElasticsearchResponse> results;
  private final String scrollId;

  // Constructor for normal usage
  public ScrollResult(List<ElasticsearchResponse> results, String scrollId) {
    this.results = results != null ? results : new ArrayList<>();
    this.scrollId = scrollId;
  }

  // Constructor required by Vert.x for JsonObject mapping
  public ScrollResult(JsonObject json) {
    // You need ElasticsearchResponse to also have a Json constructor or fromJson method
    JsonArray arr = json.getJsonArray("results", new JsonArray());
    List<ElasticsearchResponse> list = new ArrayList<>();
    for (int i = 0; i < arr.size(); i++) {
      list.add(new ElasticsearchResponse(arr.getJsonObject(i)));
    }
    this.results = list;
    this.scrollId = json.getString("scrollId");
  }

  public List<ElasticsearchResponse> getResults() {
    return results;
  }

  public String getScrollId() {
    return scrollId;
  }

  // Required by Vert.x codegen
  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    JsonArray arr = new JsonArray();
    if (results != null) {
      for (ElasticsearchResponse res : results) {
        arr.add(res.toJson());
      }
    }
    json.put("results", arr);
    json.put("scrollId", scrollId);
    return json;
  }

  @Override
  public String toString() {
    return "ScrollResult{" + "results=" + results + ", scrollId='" + scrollId + '\'' + '}';
  }
}
