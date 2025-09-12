package org.cdpg.dx.rs.query;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * QueryMapper converts validated {@link NGSILDQueryParams} into a JsonObject suitable for
 * downstream services.
 *
 * <p>Note: This class does not perform validation – it assumes inputs were validated by
 * ParamsValidator / QueryValidator beforehand.
 */
public class QueryMapper {

  private static final Logger LOGGER = LogManager.getLogger(QueryMapper.class);

  public JsonObject toJson(NGSILDQueryParams params, boolean isTemporal) {
    return toJson(params, isTemporal, false);
  }

  public JsonObject toJson(NGSILDQueryParams params, boolean isTemporal, boolean isAsyncQuery) {
    LOGGER.debug("Mapping NGSILDQueryParams: {}", params);

    JsonObject json = new JsonObject();
    JsonObject geoJson = new JsonObject();
    JsonObject temporal = new JsonObject();

    // IDs
    if (params.getId() != null) {
      JsonArray ids = new JsonArray();
      params.getId().forEach(id -> ids.add(id.toString()));
      json.put(JSON_ID, ids);
    }

    // Attribute filters
    if (params.getAttrs() != null) {
      JsonArray attrs = new JsonArray();
      params.getAttrs().forEach(attrs::add);
      json.put(JSON_ATTRIBUTE_FILTER, attrs);
    }

    // Geo query (assumes already validated)
    if (params.getGeoRel().getRelation() != null
        && params.getCoordinates() != null
        && params.getGeometry() != null
        && params.getGeoProperty() != null) {
      if (params.getGeometry().equalsIgnoreCase(GEOM_POINT)
          && params.getGeoRel().getRelation().equals(JSON_NEAR)
          && params.getGeoRel().getMaxDistance() != null) {
        String[] coords = params.getCoordinates().replaceAll("\\[|\\]", "").split(",");
        geoJson.put(JSON_LAT, Double.parseDouble(coords[0]));
        geoJson.put(JSON_LON, Double.parseDouble(coords[1]));
        geoJson.put(JSON_RADIUS, params.getGeoRel().getMaxDistance());
      } else {
        geoJson.put(JSON_GEOMETRY, params.getGeometry());
        geoJson.put(JSON_COORDINATES, params.getCoordinates());
        geoJson.put(JSON_GEOREL, params.getGeoRel().getRelation());
        if (params.getGeoRel().getMaxDistance() != null) {
          geoJson.put(JSON_MAXDISTANCE, params.getGeoRel().getMaxDistance());
        } else if (params.getGeoRel().getMinDistance() != null) {
          geoJson.put(JSON_MINDISTANCE, params.getGeoRel().getMinDistance());
        }
      }
      geoJson.put(JSON_GEOPROPERTY, params.getGeoProperty());
      json.put(GEO_QUERY, geoJson);
    }

    // Temporal query (assumes already validated)
    if (isTemporal && params.getTemporalRelation().getTimeRel() != null) {
      temporal.put(JSON_TIME, params.getTemporalRelation().getTime());
      temporal.put(JSON_ENDTIME, params.getTemporalRelation().getEndTime());
      temporal.put(JSON_TIMEREL, params.getTemporalRelation().getTimeRel());
      temporal.put(JSON_TIMEPROPERTY, params.getTemporalRelation().getTimeProperty());
      json.put(TEMPORAL_QUERY, temporal);
    }

    // Attribute query (q)
    if (params.getQ() != null) {
      json.put(JSON_ATTR_QUERY, params.getQ());
    }

    // Options, paging
    if (params.getOptions() != null) {
      json.put(IUDXQUERY_OPTIONS, params.getOptions());
    }
    if (params.getPageFrom() >= 0) {
      json.put(NGSILDQUERY_FROM, params.getPageFrom());
    } else {
      json.put(NGSILDQUERY_FROM, DEFAULT_PAGE_FROM);
    }
    if (params.getPageSize() > 0) {
      json.put(NGSILDQUERY_SIZE, params.getPageSize());
    } else {
      json.put(NGSILDQUERY_SIZE, DEFAULT_PAGE_SIZE);
    }

    // Search type flags
    json.put(JSON_SEARCH_TYPE, getSearchType(isTemporal, isAsyncQuery, params));

    LOGGER.debug("Mapped query: {}", json.encodePrettily());
    return json;
  }

  private String getSearchType(boolean isTemporal, boolean isAsyncQuery, NGSILDQueryParams params) {
    StringBuilder searchType = new StringBuilder();
    if (isTemporal) {
      searchType.append(JSON_TEMPORAL_SEARCH);
    }
    if (params.getQ() != null) {
      searchType.append(JSON_ATTRIBUTE_SEARCH);
    }
    if (params.getAttrs() != null) {
      searchType.append(JSON_RESPONSE_FILTER_SEARCH);
    }
    if (params.getGeoRel().getRelation() != null
        || params.getCoordinates() != null
        || params.getGeometry() != null
        || params.getGeoProperty() != null) {
      searchType.append(JSON_GEO_SEARCH);
    }
    // remove last char if dangling
    return searchType.substring(0, searchType.length() - 1);
  }
}
