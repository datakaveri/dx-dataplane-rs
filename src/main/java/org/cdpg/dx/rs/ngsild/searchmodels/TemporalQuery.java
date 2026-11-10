package org.cdpg.dx.rs.ngsild.searchmodels;

import io.vertx.core.json.JsonObject;

public class TemporalQuery {
    private String timerel;
    private String timeAt;
    private String endtimeAt;
    private String timeproperty;

    public String getTimerel() {
        return timerel;
    }

    public void setTimerel(String timerel) {
        this.timerel = timerel;
    }

    public String getTimeAt() {
        return timeAt;
    }

    public void setTimeAt(String timeAt) {
        this.timeAt = timeAt;
    }

    public String getEndtimeAt() {
        return endtimeAt;
    }

    public void setEndtimeAt(String endtimeAt) {
        this.endtimeAt = endtimeAt;
    }

    public String getTimeproperty() {
        return timeproperty;
    }

    public void setTimeproperty(String timeproperty) {
        this.timeproperty = timeproperty;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        if (timerel != null) json.put("timerel", timerel);
        if (timeAt != null) json.put("timeAt", timeAt);
        if (endtimeAt != null) json.put("endtimeAt", endtimeAt);
        if (timeproperty != null) json.put("timeproperty", timeproperty);
        return json;
    }
}
