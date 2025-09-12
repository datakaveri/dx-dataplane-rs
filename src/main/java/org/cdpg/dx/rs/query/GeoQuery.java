package org.cdpg.dx.rs.query;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class GeoQuery {
  private String geometry; // Point, LineString, Polygon, etc.
  private JsonArray coordinates; // Use JsonArray for nested flexibility
  private String geoproperty; // Default is usually "location"
  private GeoRelation georel; // Relation + optional distance constraints

  public String getGeometry() {
    return geometry;
  }

  public void setGeometry(String geometry) {
    this.geometry = geometry;
  }

  public JsonArray getCoordinates() {
    return coordinates;
  }

  public void setCoordinates(JsonArray coordinates) {
    this.coordinates = coordinates;
  }

  public String getGeoproperty() {
    return geoproperty;
  }

  public void setGeoproperty(String geoproperty) {
    this.geoproperty = geoproperty;
  }

  public GeoRelation getGeorel() {
    return georel;
  }

  public void setGeorel(GeoRelation georel) {
    this.georel = georel;
  }

  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    if (geometry != null) json.put("geometry", geometry);
    if (coordinates != null) json.put("coordinates", coordinates);
    if (geoproperty != null) json.put("geoproperty", geoproperty);
    if (georel != null) {
      JsonObject grJson = new JsonObject();
      if (georel.getRelation() != null) grJson.put("relation", georel.getRelation());
      if (georel.getMaxDistance() != null) grJson.put("maxDistance", georel.getMaxDistance());
      if (georel.getMinDistance() != null) grJson.put("minDistance", georel.getMinDistance());
      json.put("georel", grJson);
    }
    return json;
  }
}
