package org.cdpg.dx.rs.ngsild.service;

import static org.cdpg.dx.database.elastic.util.Constants.*;

import io.vertx.core.json.JsonArray;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.util.BoolOperator;
import org.cdpg.dx.database.elastic.util.QueryType;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;

/**
 * NewQueryMapper converts validated {@link NGSILDQueryParams} into Elasticsearch QueryModel
 * suitable for temporal entity searches.
 *
 * <p>This class handles mapping of NGSI-LD query parameters to Elasticsearch query structures,
 * including temporal queries, geo queries, ID filters, text queries, and pagination.
 */
public class NewQueryMapper {

  private static final Logger LOGGER = LogManager.getLogger(NewQueryMapper.class);

  /**
   * Maps NGSI-LD query parameters to Elasticsearch QueryModel for temporal searches.
   *
   * @param ngsildQueryParams the validated NGSI-LD query parameters
   * @return QueryModel representing the Elasticsearch query
   */
  public QueryModel mapToElasticsearchQuery(NGSILDQueryParams ngsildQueryParams) {
    LOGGER.debug("Mapping NGSI-LD parameters to Elasticsearch query: {}", ngsildQueryParams);
    LOGGER.debug("Input parameters - pageFrom: {}, pageSize: {}, pick: {}, omit: {}", 
        ngsildQueryParams.getPageFrom(), ngsildQueryParams.getPageSize(), 
        ngsildQueryParams.getPick(), ngsildQueryParams.getOmit());

    List<QueryModel> mustQueries = new ArrayList<>();
    List<QueryModel> filterQueries = new ArrayList<>();

    // Map ID filters
    if (ngsildQueryParams.getId() != null && !ngsildQueryParams.getId().isEmpty()) {
      QueryModel idQuery = mapIdQuery(ngsildQueryParams.getId());
      if (idQuery != null) {
        mustQueries.add(idQuery);
      }
    }

    // Map temporal query
    if (ngsildQueryParams.getTemporalQuery() != null) {
      QueryModel temporalQuery = mapTemporalQuery(ngsildQueryParams.getTemporalQuery());
      if (temporalQuery != null) {
        filterQueries.add(temporalQuery);
      }
    }

    // Map geo query
    if (ngsildQueryParams.getGeoRel() != null && ngsildQueryParams.getGeoRel().getRelation() != null) {
      QueryModel geoQuery = mapGeoQuery(ngsildQueryParams);
      if (geoQuery != null) {
        filterQueries.add(geoQuery);
      }
    }

    // Map text query (q parameter)
    if (ngsildQueryParams.getQ() != null && !ngsildQueryParams.getQ().trim().isEmpty()) {
      QueryModel textQuery = mapTextQuery(ngsildQueryParams.getQ());
      if (textQuery != null) {
        mustQueries.add(textQuery);
      }
    }

    // Map type filter
    if (ngsildQueryParams.getType() != null && !ngsildQueryParams.getType().trim().isEmpty()) {
      QueryModel typeQuery = mapTypeQuery(ngsildQueryParams.getType());
      if (typeQuery != null) {
        mustQueries.add(typeQuery);
      }
    }

    // Map ID pattern filter
    if (ngsildQueryParams.getIdPattern() != null && !ngsildQueryParams.getIdPattern().trim().isEmpty()) {
      QueryModel idPatternQuery = mapIdPatternQuery(ngsildQueryParams.getIdPattern());
      if (idPatternQuery != null) {
        mustQueries.add(idPatternQuery);
      }
    }

    // Create the main boolean query
    QueryModel mainQuery = createBooleanQuery(mustQueries, filterQueries);

    // Add pagination and sorting
    addPaginationAndSorting(mainQuery, ngsildQueryParams);

    // Add source filtering (pick/omit)
    addSourceFiltering(mainQuery, ngsildQueryParams);

    return mainQuery;
  }

  /**
   * Maps ID list to terms query.
   */
  private QueryModel mapIdQuery(List<URI> ids) {
    if (ids == null || ids.isEmpty()) {
      return null;
    }

    List<String> idStrings = ids.stream()
        .map(URI::toString)
        .collect(Collectors.toList());

    // Convert List<String> to JsonArray as expected by QueryModel
    JsonArray idArray = new JsonArray();
    idStrings.forEach(idArray::add);

    Map<String, Object> params = new HashMap<>();
    params.put(FIELD, "id");
    params.put(VALUE, idArray);

    return new QueryModel(QueryType.TERMS, params);
  }

  /**
   * Maps temporal query parameters to range query.
   */
  private QueryModel mapTemporalQuery(org.cdpg.dx.rs.ngsild.searchmodels.TemporalQuery temporalQuery) {
    if (temporalQuery == null) {
      return null;
    }

    String timeProperty = temporalQuery.getTimeproperty() != null 
        ? temporalQuery.getTimeproperty() 
        : "observedAt"; // Default temporal property

    Map<String, Object> rangeParams = new HashMap<>();
    rangeParams.put(FIELD, timeProperty);

    String timerel = temporalQuery.getTimerel();
    String timeAt = temporalQuery.getTimeAt();
    String endtimeAt = temporalQuery.getEndtimeAt();

    if (timerel != null && timeAt != null) {
      switch (timerel.toLowerCase()) {
        case "before":
          /*rangeParams.put(LESS_THAN, timeAt);*/
            rangeParams.put(LESS_THAN_EQUALS, timeAt);
          break;
        case "after":
          /*rangeParams.put(GREATER_THAN, timeAt);*/
            rangeParams.put(GREATER_THAN_EQUALS, timeAt);
          break;
        case "between":
          if (endtimeAt != null) {
            rangeParams.put(GREATER_THAN_EQUALS, timeAt);
            rangeParams.put(LESS_THAN_EQUALS, endtimeAt);
          } else {
            rangeParams.put(GREATER_THAN_EQUALS, timeAt);
          }
          break;
        default:
          LOGGER.warn("Unsupported temporal relation: {}", timerel);
          return null;
      }
    } else {
      return null;
    }

    return new QueryModel(QueryType.RANGE, rangeParams);
  }

  /**
   * Maps geo query parameters to geo query.
   */
  private QueryModel mapGeoQuery(NGSILDQueryParams ngsildQueryParams) {
    if (ngsildQueryParams.getGeoRel() == null || 
        ngsildQueryParams.getGeoRel().getRelation() == null ||
        ngsildQueryParams.getCoordinates() == null ||
        ngsildQueryParams.getGeometry() == null) {
      return null;
    }

    String geoProperty = ngsildQueryParams.getGeoProperty() != null 
        ? ngsildQueryParams.getGeoProperty() 
        : "location"; // Default geo property

    String relation = ngsildQueryParams.getGeoRel().getRelation();
    String geometry = ngsildQueryParams.getGeometry();
    String coordinates = ngsildQueryParams.getCoordinates();

    Map<String, Object> geoParams = new HashMap<>();
    geoParams.put(FIELD, geoProperty);

    // Parse coordinates based on geometry type
    if ("Point".equalsIgnoreCase(geometry)) {
      String[] coords = coordinates.replaceAll("\\[|\\]", "").split(",");
      if (coords.length >= 2) {
        Map<String, Double> point = new HashMap<>();
        point.put("lat", Double.parseDouble(coords[0].trim()));
        point.put("lon", Double.parseDouble(coords[1].trim()));
        geoParams.put("point", point);

        if ("near".equalsIgnoreCase(relation) && ngsildQueryParams.getGeoRel().getMaxDistance() != null) {
          geoParams.put("distance", ngsildQueryParams.getGeoRel().getMaxDistance() + "m");
          return new QueryModel(QueryType.GEO_DISTANCE, geoParams);
        }
      }
    } else if ("Polygon".equalsIgnoreCase(geometry)) {
      // For polygon, we would need to parse the coordinates array
      // This is a simplified implementation
      geoParams.put("coordinates", coordinates);
      return new QueryModel(QueryType.GEO_SHAPE, geoParams);
    }

    return null;
  }

  /**
   * Maps text query to multi-match query.
   */
  private QueryModel mapTextQuery(String textQuery) {
    if (textQuery == null || textQuery.trim().isEmpty()) {
      return null;
    }

    Map<String, Object> params = new HashMap<>();
    params.put("query", textQuery);
    
    // Convert List<String> to JsonArray as expected by QueryModel
    JsonArray fieldsArray = new JsonArray();
    fieldsArray.add("name");
    fieldsArray.add("description");
    fieldsArray.add("*.value");
    params.put("fields", fieldsArray);

    return new QueryModel(QueryType.MULTI_MATCH, params);
  }

  /**
   * Maps type filter to term query.
   */
  private QueryModel mapTypeQuery(String type) {
    if (type == null || type.trim().isEmpty()) {
      return null;
    }

    Map<String, Object> params = new HashMap<>();
    params.put(FIELD, "type");
    params.put(VALUE, type);

    return new QueryModel(QueryType.TERM, params);
  }

  /**
   * Maps ID pattern to wildcard query.
   */
  private QueryModel mapIdPatternQuery(String idPattern) {
    if (idPattern == null || idPattern.trim().isEmpty()) {
      return null;
    }

    Map<String, Object> params = new HashMap<>();
    params.put(FIELD, "id");
    params.put(VALUE, idPattern);

    return new QueryModel(QueryType.WILDCARD, params);
  }

  /**
   * Creates a boolean query combining must and filter queries.
   */
  private QueryModel createBooleanQuery(List<QueryModel> mustQueries, List<QueryModel> filterQueries) {
    List<QueryModel> allQueries = new ArrayList<>();
    allQueries.addAll(mustQueries);
    allQueries.addAll(filterQueries);

    if (allQueries.isEmpty()) {
      // Return match_all if no queries
      return new QueryModel(QueryType.MATCH_ALL, new HashMap<>());
    }

    if (allQueries.size() == 1) {
      return allQueries.get(0);
    }

    // Create boolean query with all queries in must clause
    return new QueryModel(BoolOperator.MUST, allQueries);
  }

  /**
   * Adds pagination and sorting to the query.
   */
  private void addPaginationAndSorting(QueryModel query, NGSILDQueryParams ngsildQueryParams) {
    LOGGER.debug("Adding pagination and sorting: pageFrom={}, pageSize={}", 
        ngsildQueryParams.getPageFrom(), ngsildQueryParams.getPageSize());
    
    // Set pagination
    if (ngsildQueryParams.getPageFrom() >= 0) {
      query.setOffset(String.valueOf(ngsildQueryParams.getPageFrom()));
      LOGGER.debug("Set offset to: {}", query.getOffset());
    }
    if (ngsildQueryParams.getPageSize() > 0) {
      query.setLimit(String.valueOf(ngsildQueryParams.getPageSize()));
      LOGGER.debug("Set limit to: {}", query.getLimit());
    }

    // Add default sorting by temporal property if available
    String timeProperty = ngsildQueryParams.getTemporalQuery() != null 
        ? ngsildQueryParams.getTemporalQuery().getTimeproperty()
        : "observedAt";
    
    Map<String, String> sortFields = new HashMap<>();
    sortFields.put(timeProperty, "desc"); // Default to descending order for temporal
    query.setSortFields(sortFields);
    LOGGER.debug("Set sort fields to: {}", sortFields);
  }

  /**
   * Adds source filtering based on pick/omit parameters.
   */
  private void addSourceFiltering(QueryModel query, NGSILDQueryParams ngsildQueryParams) {
      LOGGER.debug("Adding source filtering: pick={}, omit={}",
          ngsildQueryParams.getPick(), ngsildQueryParams.getOmit());
    if (ngsildQueryParams.getPick() != null && !ngsildQueryParams.getPick().isEmpty()) {
      query.setIncludeFields(ngsildQueryParams.getPick());
    }

    if (ngsildQueryParams.getOmit() != null && !ngsildQueryParams.getOmit().isEmpty()) {
      query.setExcludeFields(ngsildQueryParams.getOmit());
    }
  }
}
