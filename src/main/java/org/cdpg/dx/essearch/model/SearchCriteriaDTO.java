package org.cdpg.dx.essearch.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

public class SearchCriteriaDTO {
  private String field;
  private String searchType;
  private List<Object> values;

  public SearchCriteriaDTO() {}

  public SearchCriteriaDTO(String field, String searchType, List<Object> values) {
    this.field = field;
    this.searchType = searchType;
    this.values = values;
  }

  // ✅ Lightweight fromJson using Vert.x JsonObject
  public static SearchCriteriaDTO fromJson(JsonObject json) {
    String field = json.getString("field");
    String searchType = json.getString("searchType");
    JsonArray valuesArray = json.getJsonArray("values");
    List<Object> values = valuesArray == null ? List.of() : valuesArray.getList();

    return new SearchCriteriaDTO(field, searchType, values);
  }

  public String getField() {
    return field;
  }

  public void setField(String field) {
    this.field = field;
  }

  public String getSearchType() {
    return searchType;
  }

  public void setSearchType(String searchType) {
    this.searchType = searchType;
  }

  public List<Object> getValues() {
    return values;
  }

  public void setValues(List<Object> values) {
    this.values = values;
  }
}
