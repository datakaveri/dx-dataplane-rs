package org.cdpg.dx.rs.validation;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.wololo.jts2geojson.GeoJSONReader;

public class ParamsValidator {

  private static final Logger LOGGER = LogManager.getLogger(ParamsValidator.class);

  private static final int MAX_DISTANCE = 1000;
  private static final int MAX_POLYGON_COORDS = 10;
  private static final int MIN_POLYGON_COORDS = 4;
  private static final int MAX_LINESTRING_COORDS = 10;
  private static final int MIN_LINESTRING_COORDS = 2;

  private static final Pattern DECIMAL_PATTERN =
      Pattern.compile("^-?\\d{1,3}\\.\\d{1,6}$|^-?\\d{1,3}$");
  private static final Pattern VALIDATION_Q_ATTR_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");
  private static final Pattern VALIDATION_Q_VALUE_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]+$");
  private static final Pattern VALIDATION_ID_PATTERN =
      Pattern.compile("^urn:ngsi-ld:[a-zA-Z0-9_-]+$");
  private static final String[] VALIDATION_ALLOWED_OPERATORS = {"==", ">", "<", ">=", "<=", "!="};
  private static Set<String> validParams = new HashSet<String>();
  private static Set<String> validHeaders = new HashSet<String>();

  static {
    validParams.add(NGSILDQUERY_TYPE);
    validParams.add(NGSILDQUERY_ID);
    validParams.add(NGSILDQUERY_IDPATTERN);
    validParams.add(NGSILDQUERY_ATTRIBUTE);
    validParams.add(NGSILDQUERY_Q);
    validParams.add(NGSILDQUERY_GEOREL);
    validParams.add(NGSILDQUERY_GEOMETRY);
    validParams.add(NGSILDQUERY_COORDINATES);
    validParams.add(NGSILDQUERY_GEOPROPERTY);
    validParams.add(NGSILDQUERY_TIMEPROPERTY);
    validParams.add(NGSILDQUERY_TIME);
    validParams.add(NGSILDQUERY_TIMEREL);
    validParams.add(NGSILDQUERY_ENDTIME);
    validParams.add(NGSILDQUERY_ENTITIES);
    validParams.add(NGSILDQUERY_GEOQ);
    validParams.add(NGSILDQUERY_TEMPORALQ);
    // Need to check with the timeProperty in Post Query property for NGSI-LD release v1.3.1
    validParams.add(NGSILDQUERY_TIME_PROPERTY);
    validParams.add(NGSILDQUERY_FROM);
    validParams.add(NGSILDQUERY_SIZE);

    // for IUDX count query
    validParams.add(IUDXQUERY_OPTIONS);
  }

  static {
    validHeaders.add(HEADER_OPTIONS);
    validHeaders.add(HEADER_TOKEN);
    validHeaders.add("User-Agent");
    validHeaders.add("Content-Type");
    validHeaders.add(HEADER_CSV);
    validHeaders.add(HEADER_JSON);
    validHeaders.add(HEADER_PARQUET);
  }

  private final int maxDaysSync;
  private final int maxDaysAsync;

  public ParamsValidator(int maxDaysSync, int maxDaysAsync) {
    this.maxDaysSync = maxDaysSync;
    this.maxDaysAsync = maxDaysAsync;
  }

  /* ===== Unified recursive parameter validation ===== */
  private void validateParamsRecursive(Object value) {
    if (value instanceof JsonObject obj) {
      for (String key : obj.fieldNames()) {
        if (!validParams.contains(key)) {
          throw new DxBadRequestException("Invalid parameter: " + key);
        }
        validateParamsRecursive(obj.getValue(key));
      }
    } else if (value instanceof JsonArray arr) {
      for (Object item : arr) {
        validateParamsRecursive(item);
      }
    }
  }

  /* ===== Optimized GET query validation ===== */
  public void validateQueryParams(MultiMap params) {
    for (var entry : params.entries()) {
      if (!validParams.contains(entry.getKey())) {
        throw new DxBadRequestException("Invalid query parameter: " + entry.getKey());
      }
    }
  }

  /* ===== Optimized POST body validation ===== */
  public void validateBodyParams(JsonObject body) {
    validateParamsRecursive(body);
  }

  /* ===== Header validation ===== */
  private void validateHeaders(MultiMap headers) {
    for (String headerName : headers.names()) {
      if (!validHeaders.contains(headerName)) {
        throw new DxBadRequestException("Invalid header: " + headerName);
      }
    }
  }

  /* ---- Public validation methods ---- */

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

  public void validateDistance(String georel) {
    if (georel == null || !georel.contains("maxDistance")) return;
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
      if (timeRel != null || time != null || endTime != null || timeProperty != null) {
        throw new DxBadRequestException("/entity API does not support temporal parameters");
      }
      return;
    }

    if (timeRel == null || time == null) {
      throw new DxBadRequestException("timerel and time are mandatory for temporal queries");
    }

    if (!timeRel.equalsIgnoreCase("before")
        && !timeRel.equalsIgnoreCase("after")
        && !timeRel.equalsIgnoreCase("between")) {
      throw new DxBadRequestException("Invalid timeRel. Allowed: before, after, between");
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

  /* ---- Q-type validation ---- */

  public void validateQ(String q) {
    if (q == null || q.isBlank()) return;

    String[] attributes = q.split(";");
    for (String attr : attributes) {
      String[] terms = attr.split("((?=>)|(?<=>)|(?=<)|(?<=<)|(?<==)|(?=!)|(?<=!)|(?==)|(?===))");
      if (terms.length < 3 || terms.length > 4)
        throw new DxBadRequestException("Invalid q parameter format: " + attr);

      String jsonAttribute = terms[0];
      String jsonOperator = terms.length == 3 ? terms[1] : terms[1] + terms[2];
      String jsonValue = terms.length == 3 ? terms[2] : terms[3];

      boolean isNumeric = isNumericString(jsonValue);

      if (!isValidOperator(jsonOperator, isNumeric))
        throw new DxBadRequestException("Invalid operator in q: " + jsonOperator);
      if (!VALIDATION_Q_ATTR_PATTERN.matcher(jsonAttribute).matches())
        throw new DxBadRequestException("Invalid attribute in q: " + jsonAttribute);
      if (!VALIDATION_Q_VALUE_PATTERN.matcher(jsonValue).matches())
        throw new DxBadRequestException("Invalid value in q: " + jsonValue);
    }
  }

  private boolean isValidOperator(String op, boolean isNumeric) {
    return isNumeric ? Arrays.asList(VALIDATION_ALLOWED_OPERATORS).contains(op) : "==".equals(op);
  }

  private boolean isNumericString(String val) {
    try {
      Float.parseFloat(val);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /* ---- Private helpers for geometry ---- */

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
    if (coords.length < MIN_POLYGON_COORDS || coords.length > MAX_POLYGON_COORDS)
      throw new DxBadRequestException(
          "Polygon must have between "
              + MIN_POLYGON_COORDS
              + " and "
              + MAX_POLYGON_COORDS
              + " points");
    if (!coords[0].equals2D(coords[coords.length - 1]))
      throw new DxBadRequestException("Polygon must be closed (first and last point must match)");
    validatePrecision(coords);
  }

  private void validateLineString(JsonObject json) {
    Geometry geom = readGeometry(json);
    if (!"LineString".equalsIgnoreCase(geom.getGeometryType()))
      throw new DxBadRequestException("Invalid LineString geometry");
    Coordinate[] coords = geom.getCoordinates();
    if (coords.length < MIN_LINESTRING_COORDS || coords.length > MAX_LINESTRING_COORDS)
      throw new DxBadRequestException(
          "LineString must have between "
              + MIN_LINESTRING_COORDS
              + " and "
              + MAX_LINESTRING_COORDS
              + " points");
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
