package org.cdpg.dx.rs.rsp.gateway.util;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.util.List;
import org.cdpg.dx.rs.query.GeoQuery;
import org.cdpg.dx.rs.query.TemporalQuery;

public class QueryRequest2 {
  private List<URI> id;
  private List<String> type;
  private List<String> pick;
  private List<String> omit;
  private List<String> idPattern;
  private String q;
  private TemporalQuery temporalQ;
  private GeoQuery geoQ;
  private String options;
  private String from;
  private String size;

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

  public List<URI> getId() {
    return id;
  }

  public void setId(List<URI> id) {
    this.id = id;
  }

  public List<String> getType() {
    return type;
  }

  public void setType(List<String> type) {
    this.type = type;
  }

  public List<String> getIdPattern() {
    return idPattern;
  }

  public void setIdPattern(List<String> idPattern) {
    this.idPattern = idPattern;
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

  public JsonObject toJson() {
    JsonObject json = new JsonObject();

    if (id != null) {
      JsonArray arr = new JsonArray();
      for (URI uri : id) arr.add(uri.toString());
      json.put("id", arr);
    }
    if (type != null) json.put("type", new JsonArray(type));
    if (omit != null) json.put("omit", new JsonArray(omit));
    if (pick != null) json.put("pick", new JsonArray(pick));
    if (idPattern != null) json.put("idPattern", new JsonArray(idPattern));
    if (q != null) json.put("q", q);

    if (temporalQ != null) json.put("temporalQ", temporalQ.toJson());
    if (geoQ != null) json.put("geoQ", geoQ.toJson());

    if (options != null) json.put("options", options);
    if (from != null) json.put("from", from);
    if (size != null) json.put("size", size);

    return json;
  }
}
