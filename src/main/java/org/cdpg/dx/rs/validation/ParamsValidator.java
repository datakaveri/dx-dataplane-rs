package org.cdpg.dx.rs.validation;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.wololo.jts2geojson.GeoJSONReader;

/** ParamsValidator validates NGSI-LD request query parameters. */
public class ParamsValidator {

  private static final Logger LOGGER = LogManager.getLogger(ParamsValidator.class);

  private static final int MAX_DISTANCE = 1000;
  private static final int MAX_POLYGON_COORDS = 10;
  private static final int MIN_POLYGON_COORDS = 4;
  private static final int MAX_LINESTRING_COORDS = 10;
  private static final int MIN_LINESTRING_COORDS = 2;

  private static final Pattern DECIMAL_PATTERN =
      Pattern.compile("^-?\\d{1,3}\\.\\d{1,6}$|^-?\\d{1,3}$");

  private final int maxDaysSync;
  private final int maxDaysAsync;

  public ParamsValidator(int maxDaysSync, int maxDaysAsync) {
    this.maxDaysSync = maxDaysSync;
    this.maxDaysAsync = maxDaysAsync;
  }

  /* ---- Public validation methods ---- */

  /** Validate spatial + coordinates combination. */
  public void validateGeometry(String geom, String coordinates) {
    if (geom == null && coordinates == null) return;

    try {
      JsonObject json = new JsonObject();
      json.put("coordinates", new JsonArray(coordinates));

      switch (geom.toLowerCase()) {
        case "point":
          json.put("type", "Point");
          validatePoint(json);
          break;
        case "polygon":
          json.put("type", "Polygon");
          validatePolygon(json);
          break;
        case "linestring":
          json.put("type", "LineString");
          validateLineString(json);
          break;
        case "bbox":
          validateBbox(coordinates);
          break;
        default:
          throw new DxBadRequestException("Unsupported geometry type: " + geom);
      }
    } catch (Exception e) {
      LOGGER.error("Invalid geo parameters: {}", e.getMessage());
      throw new DxBadRequestException("Invalid geo parameters: " + e.getMessage());
    }
  }

  /** Validate distance constraints in georel. */
  public void validateDistance(String georel) {
      LOGGER.error("georel: {}", georel);
    if (georel == null || !georel.contains("near")) return;
    try {
      String[] parts = georel.split(";");
      if (parts.length != 2)
        throw new DxBadRequestException("Invalid georel format. Expected near;maxDistance=<value>");
      String[] kv = parts[1].split("=");
      if (kv.length != 2 || !"maxDistance".equalsIgnoreCase(kv[0]))
        throw new DxBadRequestException("Invalid maxDistance format");
      double value = Double.parseDouble(kv[1]);
      if (value < 0 || value > MAX_DISTANCE)
        throw new DxBadRequestException("maxDistance must be between 0 and " + MAX_DISTANCE);
    } catch (NumberFormatException e) {
      throw new DxBadRequestException("maxDistance must be a valid number");
    }
  }

  /**
   * Validate temporal query parameters.
   *
   * @param isTemporalApi true if /temporal/entity API, false if /entity API
   */
  public void validateTemporal(
      String timeRel,
      String time,
      String endTime,
      String timeProperty,
      boolean isAsync,
      boolean isTemporalApi) {

    if (!isTemporalApi) {
      // /entity API -> reject any temporal fields
      if (timeRel != null || time != null || endTime != null || timeProperty != null) {
        throw new DxBadRequestException("/entity API does not support temporal parameters");
      }
      return;
    }

    // /temporal/entity API -> enforce mandatory temporal validation
    if (timeRel == null || time == null) {
      throw new DxBadRequestException("timerel and time are mandatory for temporal queries");
    }

    if (!timeRel.equalsIgnoreCase("before")
        && !timeRel.equalsIgnoreCase("after")
        && !timeRel.equalsIgnoreCase("between")
        && !timeRel.equalsIgnoreCase("during")) {
      throw new DxBadRequestException("Invalid timeRel. Allowed: before, after, between or during");
    }

    ZonedDateTime start;
    try {
      start = ZonedDateTime.parse(time);
    } catch (Exception e) {
      throw new DxBadRequestException("time must be in ISO 8601 format");
    }

    ZonedDateTime end = null;
    if ("between".equalsIgnoreCase(timeRel)) {
      if (endTime == null)
        throw new DxBadRequestException("endTime is mandatory when timeRel=between");
      try {
        end = ZonedDateTime.parse(endTime);
        if (end.isBefore(start)) throw new DxBadRequestException("endTime must be after time");
      } catch (Exception e) {
        throw new DxBadRequestException("endTime must be in ISO 8601 format");
      }
    }

    if (timeProperty != null && !timeProperty.equals("observedAt")) {
      throw new DxBadRequestException("Unsupported timeProperty: " + timeProperty);
    }

    if (end != null) {
      long days = Duration.between(start, end).toDays();
      int limit = isAsync ? maxDaysAsync : maxDaysSync;
      if (days > limit) {
        throw new DxBadRequestException(
            "time interval greater than "
                + limit
                + " days is not allowed for "
                + (isAsync ? "async" : "sync")
                + " queries");
      }
    }
  }

  /* ---- Private helpers ---- */

  private void validatePoint(JsonObject json) {
    Geometry geom = readGeometry(json);
    if (!"Point".equalsIgnoreCase(geom.getGeometryType()))
      throw new DxBadRequestException("Invalid Point geometry");
    Coordinate[] coords = geom.getCoordinates();
    if (coords.length != 1)
      throw new DxBadRequestException("Point must have exactly one coordinate pair");
    validatePrecision(coords);
  }

  private void validatePolygon(JsonObject json) {
    Geometry geom = readGeometry(json);
    if (!"Polygon".equalsIgnoreCase(geom.getGeometryType()))
      throw new DxBadRequestException("Invalid Polygon geometry");
    Coordinate[] coords = geom.getCoordinates();
    if (coords.length < MIN_POLYGON_COORDS || coords.length > MAX_POLYGON_COORDS) {
      throw new DxBadRequestException(
          "Polygon must have between "
              + MIN_POLYGON_COORDS
              + " and "
              + MAX_POLYGON_COORDS
              + " points");
    }
    if (!coords[0].equals2D(coords[coords.length - 1]))
      throw new DxBadRequestException("Polygon must be closed (first and last point must match)");
    validatePrecision(coords);
  }

  private void validateLineString(JsonObject json) {
    Geometry geom = readGeometry(json);
    if (!"LineString".equalsIgnoreCase(geom.getGeometryType()))
      throw new DxBadRequestException("Invalid LineString geometry");
    Coordinate[] coords = geom.getCoordinates();
    if (coords.length < MIN_LINESTRING_COORDS || coords.length > MAX_LINESTRING_COORDS) {
      throw new DxBadRequestException(
          "LineString must have between "
              + MIN_LINESTRING_COORDS
              + " and "
              + MAX_LINESTRING_COORDS
              + " points");
    }
    validatePrecision(coords);
  }

  private void validateBbox(String coordinates) {
    String clean = coordinates.replaceAll("\\[", "").replaceAll("\\]", "");
    String[] parts = clean.split(",");
    if (parts.length != 4)
      throw new DxBadRequestException(
          "BBox must have exactly 2 coordinate pairs [[lon1,lat1],[lon2,lat2]]");
    try {
      for (String part : parts) Double.parseDouble(part.trim());
    } catch (NumberFormatException e) {
      throw new DxBadRequestException("BBox coordinates must be valid numbers");
    }
  }

  private Geometry readGeometry(JsonObject json) {
    GeoJSONReader reader = new GeoJSONReader();
    return reader.read(json.toString());
  }

  private void validatePrecision(Coordinate[] coords) {
    for (Coordinate c : coords) {
      if (!DECIMAL_PATTERN.matcher(Double.toString(c.x)).matches()
          || !DECIMAL_PATTERN.matcher(Double.toString(c.y)).matches()) {
        throw new DxBadRequestException("Coordinate precision must not exceed 6 decimal places");
      }
    }
  }
}
