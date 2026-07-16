package org.cdpg.dx.rs.rsp.gateway.util;

import static org.cdpg.dx.apiserver.config.ApiConstants.HEADER_TOKEN;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.*;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.exception.DxNotAcceptableException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.wololo.jts2geojson.GeoJSONReader;

public class GatewayParamValidator {
  private static final Logger LOGGER = LogManager.getLogger(GatewayParamValidator.class);

  private static final int MAX_DISTANCE = 1000;
  private static final int MAX_POLYGON_COORDS = 10;
  private static final int MIN_POLYGON_COORDS = 4;
  private static final int MAX_LINESTRING_COORDS = 10;
  private static final int MIN_LINESTRING_COORDS = 2;

  private static final int MAX_ATTRS_ITEMS = 5; // same as VALIDATION_MAX_ATTRS
  private static final int MAX_ATTR_LENGTH = 100; // same as VALIDATIONS_MAX_ATTR_LENGTH
  private static final Pattern ATTRS_REGEX = Pattern.compile("^[a-zA-Z0-9_]+$");

  private static final Pattern DECIMAL_PATTERN =
      Pattern.compile("^-?\\d{1,3}\\.\\d{1,6}$|^-?\\d{1,3}$");
  private static final Pattern VALIDATION_Q_ATTR_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");
  private static final Pattern VALIDATION_Q_VALUE_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]+$");
  private static final Pattern VALIDATION_ID_PATTERN =
      Pattern.compile("^urn:ngsi-ld:[a-zA-Z0-9_-]+$");
  private static final String[] VALIDATION_ALLOWED_OPERATORS = {"==", ">", "<", ">=", "<=", "!="};
  private static final List<String> SUPPORTED_MEDIA_TYPES =
      Arrays.asList("application/json", "application/ld+json", "application/geo+json");
  // Pattern to parse Accept header with quality values
  private static final Pattern MEDIA_TYPE_PATTERN =
      Pattern.compile("([a-zA-Z0-9*+\\-./]+)(?:;\\s*q=([0-9.]+))?");

  private static Set<String> validHeaders = new HashSet<String>();
  private static Set<String> validParamsEntities = new HashSet<String>();
  private static Set<String> validParamsPost = new HashSet<String>();
  private static Set<String> validParamsTemporalEntites = new HashSet<String>();

  static {
    validParamsTemporalEntites.add(NGSILDQUERY_TYPE);
    validParamsTemporalEntites.add(NGSILDQUERY_ID);
    validParamsTemporalEntites.add(NGSILDQUERY_IDPATTERN);
    validParamsTemporalEntites.add(NGSILDQUERY_OMIT);
    validParamsTemporalEntites.add(NGSILDQUERY_PICK);
    validParamsTemporalEntites.add(NGSILDQUERY_Q);
    validParamsTemporalEntites.add(NGSILDQUERY_GEOREL);
    validParamsTemporalEntites.add(NGSILDQUERY_GEOMETRY);
    validParamsTemporalEntites.add(NGSILDQUERY_COORDINATES);
    validParamsTemporalEntites.add(NGSILDQUERY_GEOPROPERTY);
    validParamsTemporalEntites.add(NGSILDQUERY_TIMEPROPERTY);
    validParamsTemporalEntites.add(NGSILDQUERY_TIMEAT);
    validParamsTemporalEntites.add(NGSILDQUERY_TIMEREL);
    validParamsTemporalEntites.add(NGSILDQUERY_ENDTIMEAT);
    validParamsTemporalEntites.add(NGSILDQUERY_AGGR_METHODS);
    validParamsTemporalEntites.add(NGSILDQUERY_AGGR_PERIOD_DURATION);
    validParamsTemporalEntites.add(NGSILDQUERY_ENTITIES);
    validParamsTemporalEntites.add(NGSILDQUERY_GEOQ);
    validParamsTemporalEntites.add(NGSILDQUERY_TEMPORALQ);
    validParamsTemporalEntites.add(NGSILDQUERY_FROM);
    validParamsTemporalEntites.add(NGSILDQUERY_SIZE);
    validParamsTemporalEntites.add(NGSILD_OPTIONS);
    validParamsTemporalEntites.add(NGSILDQUERY_COUNT);
    validParamsTemporalEntites.add(NGSILDQUERY_LASTN);
    validParamsTemporalEntites.add(NGSILD_FORMAT);
    validParamsTemporalEntites.add(NGSILD_ORDERBY);
  }

  static {
    validParamsEntities.add(NGSILDQUERY_TYPE);
    validParamsEntities.add(NGSILDQUERY_ID);
    // Aggregation supported only for temporal, keep here if needed later
    // validParamsEntities.add(NGSILDQUERY_AGGR_METHODS);
    // validParamsEntities.add(NGSILDQUERY_AGGR_DURATIONS);
    validParamsEntities.add(NGSILDQUERY_IDPATTERN);
    validParamsEntities.add(NGSILDQUERY_OMIT);
    validParamsEntities.add(NGSILDQUERY_PICK);
    validParamsEntities.add(NGSILDQUERY_Q);
    validParamsEntities.add(NGSILDQUERY_GEOREL);
    validParamsEntities.add(NGSILDQUERY_GEOMETRY);
    validParamsEntities.add(NGSILDQUERY_COORDINATES);
    validParamsEntities.add(NGSILDQUERY_GEOPROPERTY);
    validParamsEntities.add(NGSILDQUERY_ENTITIES);
    validParamsEntities.add(NGSILDQUERY_GEOQ);
    validParamsEntities.add(NGSILDQUERY_FROM);
    validParamsEntities.add(NGSILDQUERY_SIZE);
    validParamsEntities.add(NGSILDQUERY_COUNT);
    validParamsEntities.add(NGSILD_FORMAT);
    validParamsEntities.add(NGSILD_ORDERBY);
  }

  static {
    validParamsPost.add(NGSILDQUERY_FROM);
    validParamsPost.add(NGSILDQUERY_SIZE);
    validParamsPost.add(NGSILD_OPTIONS);
    validParamsPost.add(NGSILDQUERY_COUNT);
    validParamsPost.add(NGSILD_FORMAT);
  }

  static {
    validHeaders.add(HEADER_TOKEN);
    validHeaders.add("User-Agent");
    validHeaders.add("Content-Type");
    validHeaders.add(NGSILD_LINK);
    validHeaders.add(NGSILD_TENANT);
    validHeaders.add(NGSILD_VIA);
    validHeaders.add("Accept");
    validHeaders.add("Accept-Encoding");
  }

  private final int maxDaysSync;
  private final int maxDaysAsync;

  public GatewayParamValidator(int maxDaysSync, int maxDaysAsync) {
    this.maxDaysSync = maxDaysSync;
    this.maxDaysAsync = maxDaysAsync;
  }

  private static double getValue(String[] parts) {
    if (parts.length != 2) {
      throw new DxBadRequestException("Invalid georel format. Expected near;maxDistance=<value>");
    }

    String[] kv = parts[1].split("=");
    if (kv.length != 2) {
      throw new DxBadRequestException("Invalid georel format. Expected near;maxDistance=<value>");
    }

    String key = kv[0].trim().toLowerCase(); // normalize case
    if (!"maxdistance".equals(key)) {
      throw new DxBadRequestException("Invalid distance key. Must be maxDistance");
    }

    double value = Double.parseDouble(kv[1].trim());
    return value;
  }

  /** Parse Accept header into list of media types with quality values */
  private static List<MediaTypeWithQuality> parseAcceptHeader(String acceptHeader) {
    List<MediaTypeWithQuality> result = new ArrayList<>();

    // Split by comma
    String[] types = acceptHeader.split(",");

    for (String type : types) {
      type = type.trim();

      Matcher matcher = MEDIA_TYPE_PATTERN.matcher(type);
      if (matcher.find()) {
        String mediaType = matcher.group(1).trim();
        String qualityStr = matcher.group(2);

        double quality = 1.0; // Default quality
        if (qualityStr != null) {
          try {
            quality = Double.parseDouble(qualityStr);
            if (quality < 0.0 || quality > 1.0) {
              throw new IllegalArgumentException("Quality value must be between 0.0 and 1.0");
            }
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid quality value: " + qualityStr);
          }
        }

        result.add(new MediaTypeWithQuality(mediaType, quality));
      } else {
        throw new IllegalArgumentException("Invalid media type format: " + type);
      }
    }

    return result;
  }

  /** Get list of all parsed media types with their quality values */
  public static List<MediaTypeWithQuality> getAcceptedMediaTypes(String acceptHeader) {
    if (acceptHeader == null || acceptHeader.trim().isEmpty()) {
      return Collections.singletonList(new MediaTypeWithQuality("application/json", 1.0));
    }
    return parseAcceptHeader(acceptHeader);
  }

  /* ===== Optimized GET query validation ===== */
  public void validateQueryParamsTemporalEntities(MultiMap params) {
    for (var entry : params.entries()) {
      if (!validParamsTemporalEntites.contains(entry.getKey())) {
        throw new DxBadRequestException("Invalid query parameter: " + entry.getKey());
      }
    }
  }

  public void validateQueryParamsEntities(MultiMap params) {
    for (var entry : params.entries()) {
      if (!validParamsEntities.contains(entry.getKey())) {
        throw new DxBadRequestException("Invalid query parameter: " + entry.getKey());
      }
    }
  }

  public void validateQueryParamsPost(MultiMap params) {
    for (var entry : params.entries()) {
      if (!validParamsPost.contains(entry.getKey())) {
        throw new DxBadRequestException("Invalid query parameter: " + entry.getKey());
      }
    }
  }

  /* ===== Unified recursive parameter validation ===== */
  private void validateParamsRecursive(Object value) {
    if (value instanceof JsonObject obj) {
      for (String key : obj.fieldNames()) {
        if (!validParamsTemporalEntites.contains(key)) {
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

  public void validateGeometry(String geom, String geoRel, String coordinates) {
    LOGGER.debug(
        "Validating geometry: geom={}, geoRel= {} coordinates={}", geom, geoRel, coordinates);
    if (geom == null && geoRel == null && coordinates == null) return;

    try {
      validateGeoRel(geom, geoRel);
      validateCoordinates(geom, coordinates);
      JsonObject json = new JsonObject();
      json.put("coordinates", new JsonArray(coordinates));

      switch (Objects.requireNonNull(geom).toLowerCase()) {
        case "point":
          json.put("type", "Point");
          validatePoint(json, geoRel);
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
          throw new DxBadRequestException(expectedFormatMessage(geom));
      }
    } catch (Exception e) {
      LOGGER.error("Invalid geo parameters: {}", e.getMessage());
      throw new DxBadRequestException("Invalid geo parameters: " + e.getMessage());
    }
  }

  public void validateDistance(String georel) {
    LOGGER.debug("Georel : {}", georel);

    if (georel == null) return;

    try {
      String[] parts = georel.split(";");
      double value = getValue(parts);
      if (value < 0 || value > MAX_DISTANCE) {
        throw new DxBadRequestException("maxDistance must be between 0 and " + MAX_DISTANCE);
      }
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
      String timeAt,
      String endTimeAt,
      String timeProperty,
      boolean isAsync,
      boolean isTemporalApi) {

    if (!isTemporalApi) {
      if (timeRel != null || timeAt != null || endTimeAt != null || timeProperty != null) {
        throw new DxBadRequestException("/entity API does not support temporal parameters");
      }
      return;
    }

    if (timeRel == null || timeAt == null) {
      throw new DxBadRequestException("timerel and timeAt are mandatory for temporal queries");
    }

    if (!timeRel.equalsIgnoreCase("before")
        && !timeRel.equalsIgnoreCase("after")
        && !timeRel.equalsIgnoreCase("between")) {
      throw new DxBadRequestException("Invalid timerel. Allowed: before, after, between");
    }

    ZonedDateTime start = parseIsoTime(timeAt, "timeAt");

    ZonedDateTime end = null;
    if ("between".equalsIgnoreCase(timeRel)) {
      if (endTimeAt == null) {
        throw new DxBadRequestException("endTimeAt is mandatory when timerel=between");
      }
      try {
        end = parseIsoTime(endTimeAt, "endTimeAt");

        if (end.isBefore(start)) {
          throw new DxBadRequestException("endTimeAt must be after timeAt");
        }
      } catch (Exception ex) {
        throw new DxBadRequestException("Must be in ISO 8601 format");
      }
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

  /**
   * Parses time strings in ISO 8601 format. Accepts both ZonedDateTime and OffsetDateTime inputs
   * (e.g. "+05:30" or "[Asia/Kolkata]").
   */
  private ZonedDateTime parseIsoTime(String value, String fieldName) {
    // Trim & normalize spaces before timezone
    String normalized = value.trim().replace(" ", "+");
    try {
      return ZonedDateTime.parse(normalized);
    } catch (Exception e1) {
      try {
        return OffsetDateTime.parse(normalized).toZonedDateTime();
      } catch (Exception e2) {
        throw new DxBadRequestException(fieldName + " must be in ISO 8601 format");
      }
    }
  }

  /* ---- Q-type validation ---- */

  public void validateQ(String q, boolean isTemporalApi) {
    if (!isTemporalApi && q.isBlank()) {
      throw new DxBadRequestException("Invalid query parameter: q is null or blank in attr search");
    }
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

  public void validateAttrs(String attrs) {

    // Required but missing
    if (attrs == null || attrs.isBlank()) {
      return;
    }
    // Split by commas
    LOGGER.debug("Validating attrs param : {} ", attrs);
    String[] attrList = attrs.split(",");
    if (attrList.length > MAX_ATTRS_ITEMS) {
      throw new DxBadRequestException("Too many attributes, maximum allowed = " + MAX_ATTRS_ITEMS);
    }

    for (String attr : attrList) {
      String trimmed = attr.trim();

      // Length check
      if (trimmed.length() > MAX_ATTR_LENGTH) {
        throw new DxBadRequestException("Attribute too long: " + trimmed);
      }

      // Pattern check
      if (!ATTRS_REGEX.matcher(trimmed).matches()) {
        throw new DxBadRequestException("Invalid attribute name: " + trimmed);
      }
    }
  }

  public void validatePick(String pick) {

    // Required but missing
    if (pick == null || pick.isBlank()) {
      return;
    }
    // Split by commas
    LOGGER.debug("Validating pick param : {} ", pick);
    String[] pickList = pick.split(",");
    if (pickList.length > MAX_ATTRS_ITEMS) {
      throw new DxBadRequestException("Too many pick, maximum allowed = " + MAX_ATTRS_ITEMS);
    }

    for (String pickAtr : pickList) {
      String trimmed = pickAtr.trim();

      // Length check
      if (trimmed.length() > MAX_ATTR_LENGTH) {
        throw new DxBadRequestException("Pick too long: " + trimmed);
      }

      // Pattern check
      if (!ATTRS_REGEX.matcher(trimmed).matches()) {
        throw new DxBadRequestException("Invalid pick name: " + trimmed);
      }
    }
  }

  public void validateOmit(String omit) {

    // Required but missing
    if (omit == null || omit.isBlank()) {
      return;
    }
    // Split by commas
    LOGGER.debug("Validating omit param : {} ", omit);
    String[] omitList = omit.split(",");
    if (omitList.length > MAX_ATTRS_ITEMS) {
      throw new DxBadRequestException("Too many omit, maximum allowed = " + MAX_ATTRS_ITEMS);
    }

    for (String attr : omitList) {
      String trimmed = attr.trim();

      // Length check
      if (trimmed.length() > MAX_ATTR_LENGTH) {
        throw new DxBadRequestException("Omit too long: " + trimmed);
      }

      // Pattern check
      if (!ATTRS_REGEX.matcher(trimmed).matches()) {
        throw new DxBadRequestException("Invalid omit name: " + trimmed);
      }
    }
  }

  /* ---- Private helpers for geometry ---- */

  public void validateGeoRel(String geom, String georel) {

    if (georel == null || geom == null) return;

    if (geom.equalsIgnoreCase("polygon")
        || geom.equalsIgnoreCase("linestring")
        || geom.equalsIgnoreCase("bbox")) {
      if (!georel.equalsIgnoreCase("within") && !georel.equalsIgnoreCase("intersects")) {
        throw new DxBadRequestException("georel must be within or intersects for geometry " + geom);
      }
    } else if (geom.equalsIgnoreCase("point")) {
      if (!georel.toLowerCase().startsWith("near;")) {
        throw new DxBadRequestException("georel must start with near; for geometry Point");
      }
    } else {
      throw new DxBadRequestException("Unsupported geometry type: " + geom);
    }
  }

  public void validateCoordinates(String geom, String coordinates) {
    if (geom == null || coordinates == null) return;

    JsonArray array;
    try {
      array = new JsonArray(coordinates);
    } catch (Exception ex) {
      throw new DxBadRequestException(
          "Malformed coordinates: must be a valid JSON array. " + expectedFormatMessage(geom));
    }

    if (array.isEmpty()) {
      throw new DxBadRequestException(
          geom + " coordinates cannot be empty. " + expectedFormatMessage(geom));
    }

    LOGGER.debug("Validating coordinates for {}: {}", geom, array.encodePrettily());

    try {
      switch (geom.toLowerCase()) {
        case "point" -> {
          if (array.size() != 2) {
            throw new DxBadRequestException(
                "Point must have exactly 2 values: [lon, lat]. " + expectedFormatMessage("point"));
          }
          double lon = array.getDouble(0);
          double lat = array.getDouble(1);
          if (!DECIMAL_PATTERN.matcher(Double.toString(lon)).matches()
              || !DECIMAL_PATTERN.matcher(Double.toString(lat)).matches()) {
            throw new DxBadRequestException(
                "Point coordinate precision must not exceed 6 decimal places. "
                    + expectedFormatMessage("point"));
          }
        }

        case "linestring" -> {
          if (array.size() < MIN_LINESTRING_COORDS || array.size() > MAX_LINESTRING_COORDS) {
            throw new DxBadRequestException(
                "LineString must have between "
                    + MIN_LINESTRING_COORDS
                    + " and "
                    + MAX_LINESTRING_COORDS
                    + " points. "
                    + expectedFormatMessage("linestring"));
          }
          for (int i = 0; i < array.size(); i++) {
            if (!(array.getValue(i) instanceof JsonArray pair)) {
              throw new DxBadRequestException(
                  "Each LineString coordinate must be an array: [lon, lat]. "
                      + expectedFormatMessage("linestring"));
            }
            if (pair.size() != 2) {
              throw new DxBadRequestException(
                  "Each LineString coordinate must have 2 values: [lon, lat]. "
                      + expectedFormatMessage("linestring"));
            }
            validatePairPrecision(pair);
          }
        }

        case "polygon" -> {
          if (array.size() < 1) {
            throw new DxBadRequestException(
                "Polygon must have at least one LinearRing. " + expectedFormatMessage("polygon"));
          }

          // First element = outer ring
          JsonArray outerRing = array.getJsonArray(0);

          if (outerRing.size() < MIN_POLYGON_COORDS || outerRing.size() > MAX_POLYGON_COORDS) {
            throw new DxBadRequestException(
                "Polygon must have between "
                    + MIN_POLYGON_COORDS
                    + " and "
                    + MAX_POLYGON_COORDS
                    + " points. "
                    + expectedFormatMessage("polygon"));
          }

          for (int i = 0; i < outerRing.size(); i++) {
            if (!(outerRing.getValue(i) instanceof JsonArray pair)) {
              throw new DxBadRequestException(
                  "Each Polygon coordinate must be an array: [lon, lat]. "
                      + expectedFormatMessage("polygon"));
            }
            if (pair.size() != 2) {
              throw new DxBadRequestException(
                  "Each Polygon coordinate must have 2 values: [lon, lat]. "
                      + expectedFormatMessage("polygon"));
            }
            validatePairPrecision(pair);
          }

          // Polygon must be closed
          if (!outerRing.getJsonArray(0).equals(outerRing.getJsonArray(outerRing.size() - 1))) {
            throw new DxBadRequestException(
                "Polygon must be closed (first and last point must match). "
                    + expectedFormatMessage("polygon"));
          }
        }

        case "bbox" -> {
          if (array.size() != 2) {
            throw new DxBadRequestException(
                "BBox must have exactly 2 coordinate pairs. " + expectedFormatMessage("bbox"));
          }
          for (int i = 0; i < 2; i++) {
            if (!(array.getValue(i) instanceof JsonArray pair)) {
              throw new DxBadRequestException(
                  "Each BBox coordinate must be an array: [lon, lat]. "
                      + expectedFormatMessage("bbox"));
            }
            if (pair.size() != 2) {
              throw new DxBadRequestException(
                  "Each BBox coordinate must have 2 values: [lon, lat]. "
                      + expectedFormatMessage("bbox"));
            }
            validatePairPrecision(pair);
          }
        }

        default -> throw new DxBadRequestException("Unsupported geometry type: " + geom);
      }
    } catch (DxBadRequestException e) {
      throw e; // preserve original validation message
    } catch (Exception e) {
      throw new DxBadRequestException(
          "Invalid coordinates for geometry " + geom + ": Malformed input.");
    }
  }

  private void validatePairPrecision(JsonArray pair) {
    double lon = pair.getDouble(0);
    double lat = pair.getDouble(1);
    if (!DECIMAL_PATTERN.matcher(Double.toString(lon)).matches()
        || !DECIMAL_PATTERN.matcher(Double.toString(lat)).matches()) {
      throw new DxBadRequestException("Coordinate precision must not exceed 6 decimal places");
    }
  }

  private void validatePoint(JsonObject json, String georel) {
    Geometry geom = readGeometry(json);
    if (!"Point".equalsIgnoreCase(geom.getGeometryType())) {
      throw new DxBadRequestException("Invalid Point geometry");
    }

    Coordinate[] coords = geom.getCoordinates();
    if (coords.length != 1) {
      throw new DxBadRequestException("Point must have exactly one coordinate pair");
    }

    validatePrecision(coords);

    // Validate distance when georel is given
    if (georel != null) {
      validateDistance(georel);
    } else {
      throw new DxBadRequestException("georel with distance is required for Point geometry");
    }
  }

  private void validatePolygon(JsonObject json) {
    LOGGER.debug("Validating Polygon: {}", json.encodePrettily());

    Geometry geom = readGeometry(json);
    LOGGER.debug("Parsed geometry type: {}", geom.getGeometryType());
    if (!"Polygon".equalsIgnoreCase(geom.getGeometryType())) {
      throw new DxBadRequestException(
          "Invalid Polygon geometry. " + expectedFormatMessage("polygon"));
    }

    Coordinate[] coords = geom.getCoordinates();
    LOGGER.debug("Polygon coordinates count: {}", coords.length);

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
    try {
      JsonArray array = new JsonArray(coordinates);
      if (array.size() != 2) {
        throw new DxBadRequestException(
            "BBox must have exactly 2 coordinate pairs [[lon1,lat1],[lon2,lat2]]");
      }
      for (int i = 0; i < 2; i++) {
        JsonArray pair = array.getJsonArray(i);
        if (pair.size() != 2) {
          throw new DxBadRequestException(
              "Each BBox coordinate must have exactly 2 values [lon,lat]");
        }
        double lon = pair.getDouble(0);
        double lat = pair.getDouble(1);
        if (!DECIMAL_PATTERN.matcher(Double.toString(lon)).matches()
            || !DECIMAL_PATTERN.matcher(Double.toString(lat)).matches()) {
          throw new DxBadRequestException(
              "BBox coordinate precision must not exceed 6 decimal places");
        }
      }
    } catch (Exception e) {
      throw new DxBadRequestException("Invalid BBox coordinates: " + e.getMessage());
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

  private String expectedFormatMessage(String geom) {
    return switch (geom.toLowerCase()) {
      case "point" -> "Expected Point coordinates: [lon, lat]";
      case "polygon" -> "Expected Polygon coordinates: [[[lon,lat],[lon,lat],...,[lon,lat]]]";
      case "linestring" -> "Expected LineString coordinates: [[lon,lat],[lon,lat],...,[lon,lat]]";
      case "bbox" -> "Expected BBox coordinates: [[lon1,lat1],[lon2,lat2]]";
      default -> "Unsupported geometry type: " + geom;
    };
  }

  public void isValidQueryWithFilters(MultiMap paramsMap, JsonArray applicableFilters) {
    LOGGER.info("validation filters " + applicableFilters);
    if (applicableFilters == null || applicableFilters.isEmpty()) {
      throw new DxBadRequestException("No filters given that are applicable for RS Item.");
    }
    if (isTemporalQuery(paramsMap) && !applicableFilters.contains("TEMPORAL")) {
      throw new DxBadRequestException("Temporal parameters are not supported by RS Item.");
    }
    if (isAttributeQuery(paramsMap) && !applicableFilters.contains("ATTR")) {
      throw new DxBadRequestException("Attribute parameters are not supported by RS Item.");
    }
    if (isSpatialQuery(paramsMap) && !applicableFilters.contains("SPATIAL")) {
      throw new DxBadRequestException("Spatial parameters are not supported by RS Item.");
    }
  }

  public void isValidQueryWithFilters(String searchType, JsonArray applicableFilters) {
    LOGGER.info("validation filter {}", applicableFilters);
    if (searchType.contains("temporalSearch") && !applicableFilters.contains("TEMPORAL")) {
      throw new DxBadRequestException("Temporal parameters are not supported by RS Item.");
    }
    if (searchType.contains("attributeSearch") && !applicableFilters.contains("ATTR")) {
      throw new DxBadRequestException("Attribute parameters are not supported by RS Item.");
    }
    if (searchType.contains("geoSearch") && !applicableFilters.contains("SPATIAL")) {
      throw new DxBadRequestException("Spatial parameters are not supported by RS Item.");
    }
  }

  private Boolean isTemporalQuery(MultiMap params) {
    return params.contains(NGSILDQUERY_TIMEREL)
        || params.contains(NGSILDQUERY_TIMEAT)
        || params.contains(NGSILDQUERY_ENDTIMEAT)
        || params.contains(NGSILDQUERY_TIMEPROPERTY);
  }

  private Boolean isAttributeQuery(MultiMap params) {
    return params.contains(NGSILDQUERY_ATTRIBUTE);
  }

  private Boolean isSpatialQuery(MultiMap params) {
    return params.contains(NGSILDQUERY_GEOREL)
        || params.contains(NGSILDQUERY_GEOMETRY)
        || params.contains(NGSILDQUERY_GEOPROPERTY)
        || params.contains(NGSILDQUERY_COORDINATES);
  }

  /**
   * Validates and parses Accept header string Returns the best matching media type based on quality
   * values
   */
  public String validateAndSelectBestMediaType(String acceptHeader) {
    if (acceptHeader == null || acceptHeader.trim().isEmpty()) {
      return "application/json"; // Default
    }

    try {
      List<MediaTypeWithQuality> parsedTypes = parseAcceptHeader(acceptHeader);

      // Sort by quality value (highest first)
      parsedTypes.sort(Comparator.comparingDouble(MediaTypeWithQuality::getQuality).reversed());

      // Find first supported type
      for (MediaTypeWithQuality mediaType : parsedTypes) {
        String type = mediaType.getMediaType();

        // Handle wildcards
        if ("*/*".equals(type)) {
          return "application/json"; // Default for wildcard
        }

        if ("application/*".equals(type)) {
          return "application/json"; // Default for application wildcard
        }

        // Check if supported
        if (SUPPORTED_MEDIA_TYPES.contains(type)) {
          return type;
        }
      }

      // No supported media type found
      throw new DxNotAcceptableException(
          "Not Acceptable: Supported accept types are " + String.join(", ", SUPPORTED_MEDIA_TYPES));

    } catch (IllegalArgumentException e) {
      throw new DxBadRequestException("Invalid Accept header format: " + e.getMessage());
    }
  }

  /** Check if Accept header contains any supported media type */
  public boolean isSupportedMediaType(String acceptHeader) {
    try {
      validateAndSelectBestMediaType(acceptHeader);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public void validateAggrs(String options, String aggrMethods) {
    if (options != null && options.toLowerCase().contains("aggregatedvalues")) {
      if (aggrMethods == null || aggrMethods.isBlank()) {
        throw new DxBadRequestException("aggrMethods is required when requesting aggregatedValues");
      }
      /*if(aggrPeriodDuration == null || aggrPeriodDuration.isBlank()) {
          throw new DxBadRequestException("aggrPeriodDuration is required when requesting aggregatedValues");
      }*/
      // Further validation of aggrMethods and aggrPeriodDuration can be added here
    }
  }

  public void validatePickAndAggrs(String pick, String aggrMethods) {
    if (pick != null && !pick.isBlank() && aggrMethods != null && !aggrMethods.isBlank()) {
      if (pick.split(",").length != aggrMethods.split(",").length) {
        throw new DxBadRequestException("pick and aggrMethods must have same number");
      }
    }
  }

  /** Inner class to represent media type with quality value */
  public static class MediaTypeWithQuality {
    private final String mediaType;
    private final double quality;

    public MediaTypeWithQuality(String mediaType, double quality) {
      this.mediaType = mediaType;
      this.quality = quality;
    }

    public String getMediaType() {
      return mediaType;
    }

    public double getQuality() {
      return quality;
    }

    @Override
    public String toString() {
      return mediaType + ";q=" + quality;
    }
  }
}
