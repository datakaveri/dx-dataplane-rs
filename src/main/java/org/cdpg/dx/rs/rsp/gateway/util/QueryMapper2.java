package org.cdpg.dx.rs.rsp.gateway.util;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.*;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_COORDINATES;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_ENDTIMEAT;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_FROM;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_GEOMETRY;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_GEOPROPERTY;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_GEOREL;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_ID;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_MAXDISTANCE;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_MINDISTANCE;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_SIZE;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILDQUERY_TIMEAT;
import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.NGSILD_OPTIONS;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.util.TimeUtils;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;

/**
 * QueryMapper converts validated {@link NGSILDQueryParams} into a JsonObject suitable for
 * downstream services.
 *
 * <p>Note: This class does not perform validation – it assumes inputs were validated by
 * ParamsValidator / QueryValidator beforehand.
 */
public class QueryMapper2 {

  private static final Logger LOGGER = LogManager.getLogger(QueryMapper2.class);

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
      json.put(NGSILDQUERY_ID, ids);
    }

    if (params.getOmit() != null) {
      JsonArray omit = new JsonArray();
      params.getOmit().forEach(omit::add);
      json.put(NGSILDQUERY_OMIT, omit);
    }
    if (params.getPick() != null) {
      JsonArray pick = new JsonArray();
      params.getPick().forEach(pick::add);
      json.put(NGSILDQUERY_PICK, pick);
    }

    // Geo query (assumes already validated)
    if (params.getGeoRel().getRelation() != null
        && params.getCoordinates() != null
        && params.getGeometry() != null
        && params.getGeoProperty() != null) {
      if (params.getGeometry().equalsIgnoreCase("point")
          && params.getGeoRel().getRelation().equals("near")
          && params.getGeoRel().getMaxDistance() != null) {
        String[] coords = params.getCoordinates().replaceAll("\\[|\\]", "").split(",");
        geoJson.put("lat", Double.parseDouble(coords[0]));
        geoJson.put("lon", Double.parseDouble(coords[1]));
        geoJson.put("radius", params.getGeoRel().getMaxDistance());
      } else {
        geoJson.put(NGSILDQUERY_GEOMETRY, params.getGeometry());
        geoJson.put(NGSILDQUERY_COORDINATES, params.getCoordinates());
        geoJson.put(NGSILDQUERY_GEOREL, params.getGeoRel().getRelation());
        if (params.getGeoRel().getMaxDistance() != null) {
          geoJson.put(NGSILDQUERY_MAXDISTANCE, params.getGeoRel().getMaxDistance());
        } else if (params.getGeoRel().getMinDistance() != null) {
          geoJson.put(NGSILDQUERY_MINDISTANCE, params.getGeoRel().getMinDistance());
        }
      }
      geoJson.put(NGSILDQUERY_GEOPROPERTY, params.getGeoProperty());
      json.put(GEO_QUERY, geoJson);
    }

    // Temporal query (assumes already validated)
    // Temporal query (assumes already validated)
    if (isTemporal && params.getTemporalQuery().getTimerel() != null) {
      String normalizedTime =
          TimeUtils.parseIsoDateTime(params.getTemporalQuery().getTimeAt()).toString();
      String normalizedEndTime = null;

      if (params.getTemporalQuery().getEndtimeAt() != null) {
        normalizedEndTime =
            TimeUtils.parseIsoDateTime(params.getTemporalQuery().getEndtimeAt()).toString();
      }

      temporal.put(NGSILDQUERY_TIMEAT, normalizedTime);
      if (normalizedEndTime != null) {
        temporal.put(NGSILDQUERY_ENDTIMEAT, normalizedEndTime);
      }

      temporal.put(JSON_TIMEREL, params.getTemporalQuery().getTimerel());
      temporal.put(JSON_TIMEPROPERTY, params.getTemporalQuery().getTimeproperty());
      json.put(TEMPORAL_QUERY, temporal);
    }

    // Attribute query (q)
    if (params.getQ() != null) {
      json.put(JSON_ATTR_QUERY, params.getQ());
    }

    // Options, paging
    if (params.getOptions() != null) {
      json.put(NGSILD_OPTIONS, params.getOptions());
    }

    if ((params.getAggrMethods() != null || params.getOptions() != null)
        && params.getOptions().equalsIgnoreCase("aggregatedValues")
        && params.getPick() != null) {
      JsonArray aggrs = new JsonArray();
      params.getAggrMethods().forEach(aggrs::add);
      json.put(NGSILDQUERY_AGGR_METHODS, aggrs);
    }
    if (params.isCount()) {
      json.put(NGSILDQUERY_COUNT, true);
    }
    if (params.getOrderBy() != null) {
      json.put(NGSILD_ORDERBY, params.getOrderBy());
    }
    if (params.getLastN() > 0) {
      json.put(NGSILDQUERY_LASTN, params.getLastN());
    }
    if (params.getFormat() != null) {
      json.put(NGSILD_FORMAT, params.getFormat());
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
    if (params.getOmit() != null || params.getPick() != null) {
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
