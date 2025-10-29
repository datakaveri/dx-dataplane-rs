package org.cdpg.dx.rs.ngsild.searchmodels;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GeoQ {
  private static final Logger LOGGER = LogManager.getLogger(GeoQ.class);

  private String geometry;
  private List<Double> pointCoordinates; // For Point
  private List<List<Double>> bboxCoordinates; // For bbox
  private List<List<Double>> linestringCoordinates; // For linestring
  private List<List<Double>> polygonCoordinates; // For Polygon
  private Double lat;
  private Double lon;
  private String geoRel;
  private String geoproperty;
  private Double maxDistance;

  public GeoQ(GeoQuery geoQuery) {
    this.geometry = geoQuery.getGeometry();
    this.geoproperty = geoQuery.getGeoproperty();
    this.geoRel = geoQuery.getGeorel().getRelation();

    if (geoQuery.getCoordinates() != null) {
      JsonArray coordinates = geoQuery.getCoordinates();
      String geometryType = geometry.toLowerCase();

      switch (geometryType) {
        case "point":
          if (coordinates.size() == 2) {
            List<Double> pointCoords = List.of(coordinates.getDouble(0), coordinates.getDouble(1));
            setPointCoordinates(pointCoords);
          }
          break;
        case "bbox":
          if (coordinates.size() == 2) {
            List<List<Double>> bboxCoords =
                List.of(
                    List.of(
                        coordinates.getJsonArray(0).getDouble(0),
                        coordinates.getJsonArray(0).getDouble(1)),
                    List.of(
                        coordinates.getJsonArray(1).getDouble(0),
                        coordinates.getJsonArray(1).getDouble(1)));
            setBboxCoordinates(bboxCoords);
          }
          break;
        case "linestring":
          List<List<Double>> lineCoords = new ArrayList<>();
          for (int i = 0; i < coordinates.size(); i++) {
            JsonArray point = coordinates.getJsonArray(i);
            lineCoords.add(List.of(point.getDouble(0), point.getDouble(1)));
          }
          setLinestringCoordinates(lineCoords);
          break;
        case "polygon":
          List<List<Double>> polyCoords = new ArrayList<>();
          JsonArray ring = coordinates.getJsonArray(0); // First ring
          for (int i = 0; i < ring.size(); i++) {
            JsonArray point = ring.getJsonArray(i);
            polyCoords.add(List.of(point.getDouble(0), point.getDouble(1)));
          }
          setPolygonCoordinates(polyCoords);
          break;
      }
    }
  }

  /** Constructs a GeoQ instance from the given JSON object. */
  public GeoQ(JsonObject geoQJson) {
    this.geometry = geoQJson.getString("geometry");
    this.geoRel = geoQJson.getString("georel");
    parseGeoRel(); // Parse the geo relation to extract distances
    this.geoproperty = geoQJson.getString("geoproperty");

    // Parse coordinates based on geometry type
    if (geoQJson.containsKey("coordinates")) {
      JsonArray coordinates = geoQJson.getJsonArray("coordinates");
      String geometryType = this.geometry.toLowerCase();

      switch (geometryType) {
        case "point":
          if (coordinates.size() == 2) {
            List<Double> pointCoords = List.of(coordinates.getDouble(0), coordinates.getDouble(1));
            setPointCoordinates(pointCoords);
          }
          break;
        case "bbox":
          if (coordinates.size() == 2) {
            List<List<Double>> bboxCoords =
                List.of(
                    List.of(
                        coordinates.getJsonArray(0).getDouble(0),
                        coordinates.getJsonArray(0).getDouble(1)),
                    List.of(
                        coordinates.getJsonArray(1).getDouble(0),
                        coordinates.getJsonArray(1).getDouble(1)));
            setBboxCoordinates(bboxCoords);
          }
          break;
        case "linestring":
          List<List<Double>> lineCoords = new ArrayList<>();
          for (int i = 0; i < coordinates.size(); i++) {
            JsonArray point = coordinates.getJsonArray(i);
            lineCoords.add(List.of(point.getDouble(0), point.getDouble(1)));
          }
          setLinestringCoordinates(lineCoords);
          break;
        case "polygon":
          List<List<Double>> polyCoords = new ArrayList<>();
          JsonArray ring = coordinates.getJsonArray(0); // First ring
          for (int i = 0; i < ring.size(); i++) {
            JsonArray point = ring.getJsonArray(i);
            polyCoords.add(List.of(point.getDouble(0), point.getDouble(1)));
          }
          setPolygonCoordinates(polyCoords);
          break;
      }
    }
  }

  /**
   * Parses the geoRel string to extract the primary geo relation and any distance parameters.
   * Expected format: "relation;key=value", e.g., "near;maxDistance=1000"
   */
  private void parseGeoRel() {
    LOGGER.info("Inside parseGeoRel");
    if (this.geoRel != null && !this.geoRel.isEmpty()) {
      String[] parts = this.geoRel.split(";");
      // Primary geo relation - preserve "near" for Point geometry
      String primaryRelation = parts[0];

      // Only convert "near" to "within" for non-Point geometries
      if (primaryRelation.equalsIgnoreCase(JSON_NEAR) && !"point".equalsIgnoreCase(this.geometry)) {
        this.geoRel = JSON_WITHIN;
      } else {
        this.geoRel = primaryRelation;
      }

      if (parts.length == 2) {
        String[] distanceParts = parts[1].split("=");
        if (distanceParts.length == 2) {
          try {
            LOGGER.debug("distanceParts " + distanceParts[0] + " " + distanceParts[1]);
            double distanceValue = Double.parseDouble(distanceParts[1]);
            if (distanceParts[0].equalsIgnoreCase(NGSILDQUERY_MAXDISTANCE)) {
              this.maxDistance = distanceValue;
            }
          } catch (NumberFormatException e) {
            // Handle invalid distance format if needed (e.g., log the error)
          }
        }
      }
    }
  }

  /** Serializes this GeoQ object to a JsonObject. */
  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    json.put("geometry", geometry);
    json.put("georel", geoRel); // need to fix this
    json.put("geoproperty", geoproperty);
    if (pointCoordinates != null) {
      json.put("lat", lat);
      json.put("lon", lon);
    }
    if (bboxCoordinates != null) {
      json.put("coordinates", new JsonArray(bboxCoordinates));
    }
    if (linestringCoordinates != null) {
      json.put("coordinates", new JsonArray(Arrays.asList(linestringCoordinates.toArray())));
    }
    if (polygonCoordinates != null) {
      json.put("coordinates", new JsonArray().add(new JsonArray(polygonCoordinates)));
    }
    if (maxDistance != null) {
      json.put("radius", maxDistance);
    }
    return json;
  }

  // Getters and Setters

  public String getGeometry() {
    return geometry;
  }

  public void setGeometry(String geometry) {
    this.geometry = geometry;
  }

  public List<Double> getPointCoordinates() {
    return pointCoordinates;
  }

  public void setPointCoordinates(List<Double> pointCoordinates) {
    this.lat = pointCoordinates.get(0);
    this.lon = pointCoordinates.get(1);
    this.pointCoordinates = pointCoordinates;
  }

  public List<List<Double>> getBboxCoordinates() {
    return bboxCoordinates;
  }

  public void setBboxCoordinates(List<List<Double>> bboxCoordinates) {
    this.bboxCoordinates = bboxCoordinates;
  }

  public List<List<Double>> getLinestringCoordinates() {
    return linestringCoordinates;
  }

  public void setLinestringCoordinates(List<List<Double>> linestringCoordinates) {
    this.linestringCoordinates = linestringCoordinates;
  }

  public List<List<Double>> getPolygonCoordinates() {
    return polygonCoordinates;
  }

  public void setPolygonCoordinates(List<List<Double>> polygonCoordinates) {
    this.polygonCoordinates = polygonCoordinates;
  }

  public String getGeoRel() {
    return geoRel;
  }

  public void setGeoRel(String geoRel) {
    this.geoRel = geoRel;
    parseGeoRel();
  }

  public String getGeoproperty() {
    return geoproperty;
  }

  public void setGeoproperty(String geoproperty) {
    this.geoproperty = geoproperty;
  }

  public Double getMaxDistance() {
    return maxDistance;
  }

  public void setMaxDistance(Double maxDistance) {
    this.maxDistance = maxDistance;
  }

  public Double getLat() {
    return lat;
  }

  public void setLat(Double lat) {
    this.lat = lat;
  }

  public Double getLon() {
    return lon;
  }

  public void setLon(Double lon) {
    this.lon = lon;
  }
}
