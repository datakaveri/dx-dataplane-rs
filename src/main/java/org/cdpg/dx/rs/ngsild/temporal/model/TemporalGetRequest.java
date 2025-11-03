package org.cdpg.dx.rs.ngsild.temporal.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.util.List;
import org.cdpg.dx.rs.ngsild.searchmodels.*;

public class TemporalGetRequest {
  private List<URI> id;
  /*private List<String> attributes;*/
  /*private List<String> idPattern;*/
  private List<String> pick;
  private List<String> omit;
  private String q;
  private TemporalQuery temporalQ;
  private GeoQuery geoQ;
  private String options;
  private String from;
  private String size;
  private boolean count;
  private String sortBy;
  private String sortOrder;

  public String getSortBy() {
    return sortBy;
  }

  public void setSortBy(String sortBy) {
    this.sortBy = sortBy;
  }

  public String getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(String sortOrder) {
    this.sortOrder = sortOrder;
  }

  public List<URI> getId() {
    return id;
  }

  public void setId(List<URI> id) {
    this.id = id;
  }

  public List<String> getPick() {
    return pick;
  }

  public void setPick(List<String> pick) {
    this.pick = pick;
  }

  public List<String> getOmit() {
    return omit;
  }

  public void setOmit(List<String> omit) {
    this.omit = omit;
  }

  public String getQ() {
    return q;
  }

  public void setQ(String q) {
    this.q = q;
  }

  public TemporalQuery getTemporalQ() {
    return temporalQ;
  }

  public void setTemporalQ(TemporalQuery temporalQ) {
    this.temporalQ = temporalQ;
  }

  public GeoQuery getGeoQ() {
    return geoQ;
  }

  public void setGeoQ(GeoQuery geoQ) {
    this.geoQ = geoQ;
  }

  public String getOptions() {
    return options;
  }

  public void setOptions(String options) {
    this.options = options;
  }

  public String getFrom() {
    return from;
  }

  public void setFrom(String from) {
    this.from = from;
  }

  public String getSize() {
    return size;
  }

  public void setSize(String size) {
    this.size = size;
  }

  public boolean isCount() {
    return count;
  }

  public void setCount(boolean count) {
    this.count = count;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();

    if (id != null) {
      JsonArray arr = new JsonArray();
      for (URI uri : id) arr.add(uri.toString());
      json.put("id", arr);
    }
    if (pick != null) json.put("pick", new JsonArray(pick));
    if (omit != null) json.put("omit", new JsonArray(omit));
    if (q != null) json.put("q", q);
    if (temporalQ != null) json.put("temporalQ", temporalQ.toJson());
    if (geoQ != null) json.put("geoQ", geoQ.toJson());
    if (options != null) json.put("options", options);
    if (from != null) json.put("from", from);
    if (size != null) json.put("size", size);
    if (!count) json.put("count", false);
    if (sortBy != null) json.put("sortBy", sortBy);
    if (sortOrder != null) json.put("sortOrder", sortOrder);

    return json;
  }
}
