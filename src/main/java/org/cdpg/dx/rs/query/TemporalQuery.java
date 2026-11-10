package org.cdpg.dx.rs.query;

import io.vertx.core.json.JsonObject;

public class TemporalQuery {
  private String timerel;
  private String time;
  private String endtime;
  private String timeProperty;

  public String getTimerel() {
    return timerel;
  }

  public void setTimerel(String timerel) {
    this.timerel = timerel;
  }

  public String getTime() {
    return time;
  }

  public void setTime(String time) {
    this.time = time;
  }

  public String getEndtime() {
    return endtime;
  }

  public void setEndtime(String endtime) {
    this.endtime = endtime;
  }

  public String getTimeProperty() {
    return timeProperty;
  }

  public void setTimeProperty(String timeProperty) {
    this.timeProperty = timeProperty;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    if (timerel != null) json.put("timerel", timerel);
    if (time != null) json.put("time", time);
    if (endtime != null) json.put("endtime", endtime);
    if (timeProperty != null) json.put("timeProperty", timeProperty);
    return json;
  }
}
