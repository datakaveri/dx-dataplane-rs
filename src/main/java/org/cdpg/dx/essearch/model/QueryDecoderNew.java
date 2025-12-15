package org.cdpg.dx.essearch.model;

import static org.cdpg.dx.database.elastic.util.Constants.FIELD;
import static org.cdpg.dx.database.elastic.util.Constants.VALUE;
import static org.cdpg.dx.essearch.util.Constants.*;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.exception.DxEsException;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.util.QueryType;
import org.cdpg.dx.essearch.newmodel.AttributeQueryFiltersDecorator;
import org.cdpg.dx.essearch.newmodel.GeoQueryFiltersDecorator;
import org.cdpg.dx.essearch.newmodel.TemporalQueryFiltersDecorator;
import org.cdpg.dx.rs.ngsild.queryparams.NGSILDQueryParams;
import org.cdpg.dx.rs.ngsild.searchmodels.GeoQ;
import org.cdpg.dx.rs.ngsild.searchmodels.GeoQuery;

public class QueryDecoderNew {
  private static final Logger LOGGER = LogManager.getLogger(QueryDecoderNew.class);
  private boolean isTemporal = false;
  private boolean isGeoSearch = false;
  private boolean isResponseFilter = false;
  private boolean isAttributeSearch = false;
  private String timeLimit;

  public QueryDecoderNew(String timeLimit) {
    this.timeLimit = timeLimit;
  }

  public QueryModel buildGetTemporalEntityDataQuery(NGSILDQueryParams ngsildQueryParams) {
    Map<FilterType, List<QueryModel>> queryMap = new HashMap<>();
    for (FilterType filterType : FilterType.values()) {
      queryMap.put(filterType, new ArrayList<>());
    }
    LOGGER.debug("Mapping NGSI-LD parameters to Elasticsearch query: {}", ngsildQueryParams);
    // Map ID filters
    /*if (ngsildQueryParams.getId() != null && !ngsildQueryParams.getId().isEmpty()) {
    QueryModel idQuery = mapIdQuery(ngsildQueryParams.getId());
    if (idQuery != null) {
        */
    /*mustQueries.add(idQuery);*/
    /*
            queryMap.computeIfAbsent(FilterType.SHOULD, k -> new ArrayList<>()).add(idQuery);
        }
    }*/

    if (ngsildQueryParams.getTemporalQuery() != null) {
      if (ngsildQueryParams.getTemporalQuery().getTimerel() == null
          || ngsildQueryParams.getTemporalQuery().getTimerel().isEmpty()) {
        throw new DxEsException("Time relation is required for temporal queries");
      } else {
        int defaultDateLimit = 0;
        if (ngsildQueryParams.getTemporalQuery().getTimerel() != null
            && ngsildQueryParams.getTemporalQuery().getTimeAt() != null) {
          defaultDateLimit = Integer.parseInt(timeLimit.split(",")[2]);
        }

        new TemporalQueryFiltersDecorator(
                queryMap, ngsildQueryParams.getTemporalQuery(), defaultDateLimit)
            .add();
        isTemporal = true;
      }
    }

    if (ngsildQueryParams.getGeoRel().getRelation() != null) {
      if (ngsildQueryParams.getGeoRel() == null
          || ngsildQueryParams.getGeoRel().getRelation() == null
          || ngsildQueryParams.getCoordinates() == null
          || ngsildQueryParams.getGeometry() == null) {
        return null;
      } else {
        GeoQuery geoQuery = new GeoQuery();
        geoQuery.setCoordinates(new JsonArray(ngsildQueryParams.getCoordinates()));
        geoQuery.setGeometry(ngsildQueryParams.getGeometry());
        geoQuery.setGeoproperty(ngsildQueryParams.getGeoProperty());
        geoQuery.setGeorel(ngsildQueryParams.getGeoRel());
        GeoQ geoQ = new GeoQ(geoQuery);
        new GeoQueryFiltersDecorator(queryMap, geoQ).add();
        isGeoSearch = true;
      }
    }

    if (ngsildQueryParams.getQ() != null) {
      JsonArray query = new JsonArray();
      String[] qterms = ngsildQueryParams.getQ().split(",");
      for (String term : qterms) {
        query.add(getQueryTerms(term));
      }
      JsonObject qJson = new JsonObject();
      qJson.put(ATTRIBUTE_QUERY_KEY, query);
      LOGGER.debug("Attribute Query JSON: {}", qJson);
      new AttributeQueryFiltersDecorator(queryMap, qJson).add();
      isAttributeSearch = true;
    }

    QueryModel q = new QueryModel();
    q.setQueries(getBoolQuery(queryMap));

    if (ngsildQueryParams.getPageSize() > 0) {
      q.setLimit(String.valueOf(ngsildQueryParams.getPageSize()));
      LOGGER.debug("Limit set to: {}", ngsildQueryParams.getPageSize());
    }

    if (ngsildQueryParams.getPageFrom() >= 0) {
      q.setOffset(String.valueOf(ngsildQueryParams.getPageFrom()));
      LOGGER.debug("Offset set to: {}", ngsildQueryParams.getPageFrom());
    }

    if (ngsildQueryParams.getPick() != null && !ngsildQueryParams.getPick().isEmpty()) {
      q.setIncludeFields(ngsildQueryParams.getPick());
      LOGGER.debug("Include fields set to: {}", ngsildQueryParams.getPick());
    }
    if (ngsildQueryParams.getOmit() != null && !ngsildQueryParams.getOmit().isEmpty()) {
      q.setExcludeFields(ngsildQueryParams.getOmit());
      LOGGER.debug("Exclude fields set to: {}", ngsildQueryParams.getOmit());
    }

    if (ngsildQueryParams.getLastN() > 0) {
      q.setLimit(String.valueOf(ngsildQueryParams.getLastN()));
      LOGGER.debug(
          "LastN set to: {} and because of lastN limit change to lastN",
          ngsildQueryParams.getLastN());
      Map<String, String> sortFields = new HashMap<>();
      sortFields.put(ngsildQueryParams.getTemporalQuery().getTimeproperty(), "desc");
      q.setSortFields(sortFields);
      LOGGER.debug("Sort fields set to: {} due to lastN in desc order", sortFields);
    }

    /*if(ngsildQueryParams.getOrderBy()!=null && !ngsildQueryParams.getOrderBy().isEmpty()){
      Map<String, String> sortFields = new HashMap<>();
      String[] orderByParams = ngsildQueryParams.getOrderBy().split(":");
      if(orderByParams.length==2){
        sortFields.put(orderByParams[0], orderByParams[1]);
      }else{
        sortFields.put(orderByParams[0], "desc");
      }
      q.setSortFields(sortFields);
      LOGGER.debug("Sort fields set to: {} ", sortFields);
    }*/

    if (ngsildQueryParams.getOrderBy() != null
        && !ngsildQueryParams.getOrderBy().isEmpty()
        && ngsildQueryParams.getLastN() <= 0) {
      String orderBy = ngsildQueryParams.getOrderBy().trim();
      Map<String, String> sortFields =
          Arrays.stream(orderBy.split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .map(
                  s -> {
                    String[] parts = s.split(":", 2);
                    String field = parts[0].trim();
                    String direction = "desc";
                    if (parts.length == 2 && parts[1] != null && !parts[1].trim().isEmpty()) {
                      String dir = parts[1].trim().toLowerCase();
                      if ("asc".equals(dir) || "desc".equals(dir)) {
                        direction = dir;
                      }
                    }
                    return new AbstractMap.SimpleEntry<>(field, direction);
                  })
              .filter(e -> e.getKey() != null && !e.getKey().isEmpty())
              .collect(
                  Collectors.toMap(
                      Map.Entry::getKey,
                      Map.Entry::getValue,
                      (existing, replacement) -> existing,
                      LinkedHashMap::new));

      if (!sortFields.isEmpty()) {
        q.setSortFields(sortFields);
        LOGGER.debug("Sort fields set to: {} ", sortFields);
      }
    }

    // Handle aggregations if supplied
    if (ngsildQueryParams.getAggrMethods() != null
        && !ngsildQueryParams.getAggrMethods().isEmpty()) {
      // ETSI NGSI-LD: aggrMethods is only applicable if aggregatedValues is present in format or
      // options
      boolean wantsAggregated = false;
      String opts = ngsildQueryParams.getOptions();
      String format = ngsildQueryParams.getFormat();
      if (opts != null && opts.toLowerCase().contains("aggregatedvalues")) wantsAggregated = true;
      if (format != null && format.toLowerCase().contains("aggregatedvalues"))
        wantsAggregated = true;
      if (!wantsAggregated) {
        throw new DxBadRequestException(
            "aggrMethods is only applicable when format=aggregatedValues or options includes 'aggregatedValues'");
      }
      try {
        Map<String, org.cdpg.dx.database.elastic.model.QueryModel> aggrMap =
            parseAggregations(
                ngsildQueryParams, ngsildQueryParams.getTemporalQuery().getTimeproperty());
        q.setAggregationsMap(aggrMap);
        // Also set the aggregations list expected by ElasticsearchServiceImpl
        if (aggrMap != null && !aggrMap.isEmpty()) {
          List<org.cdpg.dx.database.elastic.model.QueryModel> aggrList = new ArrayList<>();
          for (Map.Entry<String, org.cdpg.dx.database.elastic.model.QueryModel> e :
              aggrMap.entrySet()) {
            org.cdpg.dx.database.elastic.model.QueryModel qm = e.getValue();
            qm.setAggregationName(e.getKey());
            aggrList.add(qm);
          }
          q.setAggregations(aggrList);
        }
      } catch (Exception e) {
        throw new DxBadRequestException("Invalid aggregation specification: " + e.getMessage());
      }
    }

    return q;
  }

  /** Maps ID list to terms query. */
  private QueryModel mapIdQuery(List<URI> ids) {
    if (ids == null || ids.isEmpty()) {
      return null;
    }

    List<String> idStrings = ids.stream().map(URI::toString).collect(Collectors.toList());

    // Convert List<String> to JsonArray as expected by QueryModel
    JsonArray idArray = new JsonArray();
    idStrings.forEach(idArray::add);

    Map<String, Object> params = new HashMap<>();
    params.put(FIELD, "id");
    params.put(VALUE, idArray);
    LOGGER.debug("ID Terms Query Params: {}", params);
    return new QueryModel(QueryType.TERMS, params);
  }

  JsonObject getQueryTerms(final String queryTerms) {
    JsonObject json = new JsonObject();
    String jsonOperator = "";
    String jsonValue = "";
    String jsonAttribute = "";

    String[] attributes = queryTerms.split(",");
    LOGGER.info("Attributes : {} ", attributes);

    for (String attr : attributes) {

      String[] attributeQueryTerms =
          attr.split("((?=>)|(?<=>)|(?=<)|(?<=<)|(?<==)|(?=!)|(?<=!)|(?==)|(?===))");
      LOGGER.info(Arrays.stream(attributeQueryTerms).collect(Collectors.toList()));
      LOGGER.info(attributeQueryTerms.length);
      if (attributeQueryTerms.length == 3) {
        jsonOperator = attributeQueryTerms[1];
        jsonValue = attributeQueryTerms[2];
        json.put(OPERATOR, jsonOperator).put(VALUE, jsonValue);
      } else if (attributeQueryTerms.length == 4) {
        jsonOperator = attributeQueryTerms[1].concat(attributeQueryTerms[2]);
        jsonValue = attributeQueryTerms[3];
        json.put(OPERATOR, jsonOperator).put(VALUE, jsonValue);
      } else {
        throw new DxBadRequestException("Invalid attribute query");
      }
      jsonAttribute = attributeQueryTerms[0];
      json.put(ATTRIBUTE_KEY, jsonAttribute);
    }

    return json;
  }

  private QueryModel getBoolQuery(Map<FilterType, List<QueryModel>> filterQueries) {
    QueryModel boolQuery = new QueryModel(QueryType.BOOL);
    List<QueryModel> mustQueries =
        filterQueries.getOrDefault(FilterType.MUST, Collections.emptyList());
    List<QueryModel> filterQueriesList =
        filterQueries.getOrDefault(FilterType.FILTER, Collections.emptyList());
    List<QueryModel> mustNotQueries =
        filterQueries.getOrDefault(FilterType.MUST_NOT, Collections.emptyList());
    List<QueryModel> shouldQueries =
        filterQueries.getOrDefault(FilterType.SHOULD, Collections.emptyList());

    if (!mustQueries.isEmpty()) {
      boolQuery.setMustQueries(mustQueries);
    }
    if (!filterQueriesList.isEmpty()) {
      boolQuery.setFilterQueries(filterQueriesList);
    }
    if (!mustNotQueries.isEmpty()) {
      boolQuery.setMustNotQueries(mustNotQueries);
    }
    if (!shouldQueries.isEmpty()) {
      boolQuery.setShouldQueries(shouldQueries);
    }
    return boolQuery;
  }

  public QueryModel buildGetTemporalEntityCountQuery(NGSILDQueryParams ngsildQueryParams) {
    Map<FilterType, List<QueryModel>> queryMap = new HashMap<>();
    for (FilterType filterType : FilterType.values()) {
      queryMap.put(filterType, new ArrayList<>());
    }
    LOGGER.debug("Mapping NGSI-LD parameters to Elasticsearch query: {}", ngsildQueryParams);

    if (ngsildQueryParams.getTemporalQuery() != null) {
      if (ngsildQueryParams.getTemporalQuery().getTimerel() == null
          || ngsildQueryParams.getTemporalQuery().getTimerel().isEmpty()) {
        throw new DxEsException("Time relation is required for temporal queries");
      } else {
        int defaultDateLimit = 0;
        if (ngsildQueryParams.getTemporalQuery().getTimerel() != null
            && ngsildQueryParams.getTemporalQuery().getTimeAt() != null) {
          defaultDateLimit = Integer.parseInt(timeLimit.split(",")[2]);
        }

        new TemporalQueryFiltersDecorator(
                queryMap, ngsildQueryParams.getTemporalQuery(), defaultDateLimit)
            .add();
        isTemporal = true;
      }
    }

    if (ngsildQueryParams.getGeoRel().getRelation() != null) {
      if (ngsildQueryParams.getGeoRel() == null
          || ngsildQueryParams.getGeoRel().getRelation() == null
          || ngsildQueryParams.getCoordinates() == null
          || ngsildQueryParams.getGeometry() == null) {
        return null;
      } else {
        GeoQuery geoQuery = new GeoQuery();
        geoQuery.setCoordinates(new JsonArray(ngsildQueryParams.getCoordinates()));
        geoQuery.setGeometry(ngsildQueryParams.getGeometry());
        geoQuery.setGeoproperty(ngsildQueryParams.getGeoProperty());
        geoQuery.setGeorel(ngsildQueryParams.getGeoRel());
        GeoQ geoQ = new GeoQ(geoQuery);
        new GeoQueryFiltersDecorator(queryMap, geoQ).add();
        isGeoSearch = true;
      }
    }
    if (ngsildQueryParams.getQ() != null) {
      JsonArray query = new JsonArray();
      String[] qterms = ngsildQueryParams.getQ().split(",");
      for (String term : qterms) {
        query.add(getQueryTerms(term));
      }
      JsonObject qJson = new JsonObject();
      qJson.put(ATTRIBUTE_QUERY_KEY, query);
      LOGGER.debug("Attribute Query JSON: {}", qJson);
      new AttributeQueryFiltersDecorator(queryMap, qJson).add();
      isAttributeSearch = true;
    }

    QueryModel q = new QueryModel();
    q.setQueries(getBoolQuery(queryMap));
    return q;
  }

  public QueryModel buildEntitiesAttributeDataQuery(NGSILDQueryParams ngsildQueryParams) {
    Map<FilterType, List<QueryModel>> queryMap = new HashMap<>();
    for (FilterType filterType : FilterType.values()) {
      queryMap.put(filterType, new ArrayList<>());
    }
    LOGGER.debug("Mapping NGSI-LD parameters to Elasticsearch query: {}", ngsildQueryParams);
    // Map ID filters
    /*if (ngsildQueryParams.getId() != null && !ngsildQueryParams.getId().isEmpty()) {
    QueryModel idQuery = mapIdQuery(ngsildQueryParams.getId());
    if (idQuery != null) {
        */
    /*mustQueries.add(idQuery);*/
    /*
            queryMap.computeIfAbsent(FilterType.SHOULD, k -> new ArrayList<>()).add(idQuery);
        }
    }*/

    if (ngsildQueryParams.getGeoRel().getRelation() != null) {
      if (ngsildQueryParams.getGeoRel() == null
          || ngsildQueryParams.getGeoRel().getRelation() == null
          || ngsildQueryParams.getCoordinates() == null
          || ngsildQueryParams.getGeometry() == null) {
        return null;
      } else {
        GeoQuery geoQuery = new GeoQuery();
        geoQuery.setCoordinates(new JsonArray(ngsildQueryParams.getCoordinates()));
        geoQuery.setGeometry(ngsildQueryParams.getGeometry());
        geoQuery.setGeoproperty(ngsildQueryParams.getGeoProperty());
        geoQuery.setGeorel(ngsildQueryParams.getGeoRel());
        GeoQ geoQ = new GeoQ(geoQuery);
        new GeoQueryFiltersDecorator(queryMap, geoQ).add();
        isGeoSearch = true;
      }
    }

    if (ngsildQueryParams.getQ() != null) {
      JsonArray query = new JsonArray();
      String[] qterms = ngsildQueryParams.getQ().split(",");
      for (String term : qterms) {
        query.add(getQueryTerms(term));
      }
      JsonObject qJson = new JsonObject();
      qJson.put(ATTRIBUTE_QUERY_KEY, query);
      LOGGER.debug("Atr Query JSON: {}", qJson);
      new AttributeQueryFiltersDecorator(queryMap, qJson).add();
      isAttributeSearch = true;
    }

    QueryModel q = new QueryModel();
    q.setQueries(getBoolQuery(queryMap));

    if (ngsildQueryParams.getPageSize() > 0) {
      q.setLimit(String.valueOf(ngsildQueryParams.getPageSize()));
      LOGGER.debug("Limit set to: {}", ngsildQueryParams.getPageSize());
    }

    if (ngsildQueryParams.getPageFrom() >= 0) {
      q.setOffset(String.valueOf(ngsildQueryParams.getPageFrom()));
      LOGGER.debug("Offset set to: {}", ngsildQueryParams.getPageFrom());
    }

    if (ngsildQueryParams.getPick() != null && !ngsildQueryParams.getPick().isEmpty()) {
      q.setIncludeFields(ngsildQueryParams.getPick());
      LOGGER.debug("Include fields set to: {}", ngsildQueryParams.getPick());
    }
    if (ngsildQueryParams.getOmit() != null && !ngsildQueryParams.getOmit().isEmpty()) {
      q.setExcludeFields(ngsildQueryParams.getOmit());
      LOGGER.debug("Exclude fields set to: {}", ngsildQueryParams.getOmit());
    }

    if (ngsildQueryParams.getOrderBy() != null
        && !ngsildQueryParams.getOrderBy().isEmpty()
        && ngsildQueryParams.getLastN() <= 0) {
      String orderBy = ngsildQueryParams.getOrderBy().trim();
      Map<String, String> sortFields =
          Arrays.stream(orderBy.split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .map(
                  s -> {
                    String[] parts = s.split(":", 2);
                    String field = parts[0].trim();
                    String direction = "desc";
                    if (parts.length == 2 && parts[1] != null && !parts[1].trim().isEmpty()) {
                      String dir = parts[1].trim().toLowerCase();
                      if ("asc".equals(dir) || "desc".equals(dir)) {
                        direction = dir;
                      }
                    }
                    return new AbstractMap.SimpleEntry<>(field, direction);
                  })
              .filter(e -> e.getKey() != null && !e.getKey().isEmpty())
              .collect(
                  Collectors.toMap(
                      Map.Entry::getKey,
                      Map.Entry::getValue,
                      (existing, replacement) -> existing,
                      LinkedHashMap::new));

      if (!sortFields.isEmpty()) {
        q.setSortFields(sortFields);
        LOGGER.debug("Sort fields set to: {} ", sortFields);
      }
    }

    return q;
  }

  public QueryModel buildEntitiesAttributeCountQuery(NGSILDQueryParams ngsildQueryParams) {
    Map<FilterType, List<QueryModel>> queryMap = new HashMap<>();
    for (FilterType filterType : FilterType.values()) {
      queryMap.put(filterType, new ArrayList<>());
    }
    LOGGER.debug("Mapping NGSI-LD parameters to Elasticsearch query: {}", ngsildQueryParams);

    if (ngsildQueryParams.getGeoRel().getRelation() != null) {
      if (ngsildQueryParams.getGeoRel() == null
          || ngsildQueryParams.getGeoRel().getRelation() == null
          || ngsildQueryParams.getCoordinates() == null
          || ngsildQueryParams.getGeometry() == null) {
        return null;
      } else {
        GeoQuery geoQuery = new GeoQuery();
        geoQuery.setCoordinates(new JsonArray(ngsildQueryParams.getCoordinates()));
        geoQuery.setGeometry(ngsildQueryParams.getGeometry());
        geoQuery.setGeoproperty(ngsildQueryParams.getGeoProperty());
        geoQuery.setGeorel(ngsildQueryParams.getGeoRel());
        GeoQ geoQ = new GeoQ(geoQuery);
        new GeoQueryFiltersDecorator(queryMap, geoQ).add();
        isGeoSearch = true;
      }
    }
    if (ngsildQueryParams.getQ() != null) {
      JsonArray query = new JsonArray();
      String[] qterms = ngsildQueryParams.getQ().split(",");
      for (String term : qterms) {
        query.add(getQueryTerms(term));
      }
      JsonObject qJson = new JsonObject();
      qJson.put(ATTRIBUTE_QUERY_KEY, query);
      LOGGER.debug("Q Query JSON: {}", qJson);
      new AttributeQueryFiltersDecorator(queryMap, qJson).add();
      isAttributeSearch = true;
    }

    QueryModel q = new QueryModel();
    q.setQueries(getBoolQuery(queryMap));
    return q;
  }

  /**
   * Parse aggrMethods and aggrDurations from NGSILDQueryParams and build a map of QueryModel
   * aggregations Expected aggrMethods format: methodName:field or methodName (for time histograms)
   * Supported methods: avg,sum,min,max,value_count,cardinality,terms,histogram aggrDurations aligns
   * by position and used for histogram interval (ISO-8601 duration or seconds)
   */
  private Map<String, org.cdpg.dx.database.elastic.model.QueryModel> parseAggregations(
      NGSILDQueryParams params, String timeProperty) {
    Map<String, org.cdpg.dx.database.elastic.model.QueryModel> map = new HashMap<>();
    List<String> methods = params.getAggrMethods();

    if (methods == null || methods.isEmpty())
      throw new IllegalArgumentException("aggrMethods requires");
    ;

    // Per user request: use `pick` (picked attributes) as the aggregation targets.
    // The i-th method in aggrMethods applies to the i-th picked field.
    List<String> picks = params.getPick();
    if (picks == null || picks.isEmpty()) {
      throw new IllegalArgumentException(
          "aggrMethods requires pick to be specified (pick parameter)");
    }
    String period = params.getAggrPeriodDuration();

    for (int i = 0; i < methods.size(); i++) {
      String method = methods.get(i).trim().toLowerCase();
      if (i >= picks.size()) {
        throw new IllegalArgumentException(
            "aggrMethods and pick length mismatch: method at index "
                + i
                + " has no corresponding picked field");
      }
      String field = picks.get(i).trim();

      // Only allowed ETSI NGSI-LD aggregation methods
      switch (method) {
        case "totalcount":
        case "distinctcount":
        case "sum":
        case "avg":
        case "min":
        case "max":
        case "stddev":
        case "sumsq":
          break;
        default:
          throw new IllegalArgumentException(
              "Unsupported aggregation method (must be one of totalCount, distinctCount, sum, avg, min, max, stddev): "
                  + method);
      }

      Map<String, Object> aggrParams = new HashMap<>();
      aggrParams.put(org.cdpg.dx.database.elastic.util.Constants.FIELD, field);

      switch (method) {
        case "totalcount":
          {
            String aggName = "totalCount_" + field;
            map.put(
                aggName,
                new org.cdpg.dx.database.elastic.model.QueryModel(
                    org.cdpg.dx.database.elastic.util.AggregationType.VALUE_COUNT, aggrParams));
            break;
          }
        case "distinctcount":
          {
            String aggName = "distinctCount_" + field;
            map.put(
                aggName,
                new org.cdpg.dx.database.elastic.model.QueryModel(
                    org.cdpg.dx.database.elastic.util.AggregationType.CARDINALITY, aggrParams));
            break;
          }
        case "sum":
          {
            String aggName = "sum_" + field;
            map.put(
                aggName,
                new org.cdpg.dx.database.elastic.model.QueryModel(
                    org.cdpg.dx.database.elastic.util.AggregationType.SUM, aggrParams));
            break;
          }
        case "avg":
          {
            String aggName = "avg_" + field;
            map.put(
                aggName,
                new org.cdpg.dx.database.elastic.model.QueryModel(
                    org.cdpg.dx.database.elastic.util.AggregationType.AVG, aggrParams));
            break;
          }
        case "min":
          {
            String aggName = "min_" + field;
            map.put(
                aggName,
                new org.cdpg.dx.database.elastic.model.QueryModel(
                    org.cdpg.dx.database.elastic.util.AggregationType.MIN, aggrParams));
            break;
          }
        case "max":
          {
            String aggName = "max_" + field;
            map.put(
                aggName,
                new org.cdpg.dx.database.elastic.model.QueryModel(
                    org.cdpg.dx.database.elastic.util.AggregationType.MAX, aggrParams));
            break;
          }
        case "stddev":
        case "sumsq":
          {
            // create/merge extended_stats per field
            String extAggName = "extendedStats_" + field;
            if (!map.containsKey(extAggName)) {
              map.put(
                  extAggName,
                  new org.cdpg.dx.database.elastic.model.QueryModel(
                      org.cdpg.dx.database.elastic.util.AggregationType.EXTENDED_STATS,
                      aggrParams));
            }
            break;
          }
      }
    }

    // If a period is supplied, ES date_histogram must be added as a parent aggregation that buckets
    // by time.
    if (period != null && !period.isBlank()) {
      // convert ISO-8601 period (PT4M) to ES interval string (e.g., '4m' or 'PT4M' -> '4m')
      String interval = isoDurationToEsInterval(period);
      // wrap existing aggregations under a date_histogram
      Map<String, org.cdpg.dx.database.elastic.model.QueryModel> wrapped = new HashMap<>();
      Map<String, Object> dhParams = new HashMap<>();
      // Use the timeProperty (temporal property) for bucketing
      String timeField = timeProperty != null ? timeProperty : "observationDateTime";
      dhParams.put(org.cdpg.dx.database.elastic.util.Constants.FIELD, timeField);
      // Prefer calendar interval for larger units, fixed interval for seconds/minutes/hours
      if (interval.endsWith("d") || interval.endsWith("M") || interval.endsWith("y")) {
        dhParams.put("calendar_interval", interval);
      } else {
        dhParams.put("fixed_interval", interval);
      }
      // Use HISTOGRAM on the time field with interval in seconds (double)
      // convert interval string to seconds
      double secondsInterval = isoIntervalToSeconds(interval);
      dhParams.put("interval", secondsInterval);
      // Use DATE_HISTOGRAM aggregation type so we generate an ES date_histogram instead
      org.cdpg.dx.database.elastic.model.QueryModel dh =
          new org.cdpg.dx.database.elastic.model.QueryModel(
              org.cdpg.dx.database.elastic.util.AggregationType.DATE_HISTOGRAM, dhParams);
      dh.setAggregationsMap(map);
      wrapped.put("results", dh);
      return wrapped;
    }

    return map;
  }

  private String isoDurationToEsInterval(String dur) {
    // naive conversion from ISO-8601 duration (PT4M -> 4m, PT1H -> 1h, P1D -> 1d)
    try {
      if (dur == null || dur.isBlank()) return "1h";
      dur = dur.trim().toUpperCase();
      if (dur.startsWith("PT")) {
        // time-based
        dur = dur.substring(2);
        if (dur.endsWith("H")) return dur.replace("H", "h");
        if (dur.endsWith("M")) return dur.replace("M", "m");
        if (dur.endsWith("S")) return dur.replace("S", "s");
      } else if (dur.startsWith("P")) {
        // date-based
        dur = dur.substring(1);
        if (dur.endsWith("D")) return dur.replace("D", "d");
        if (dur.endsWith("M")) return dur.replace("M", "M");
        if (dur.endsWith("Y")) return dur.replace("Y", "y");
      }
      // fallback to raw value
      return dur;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid aggrPeriodDuration: " + dur);
    }
  }

  private double isoIntervalToSeconds(String s) {
    // s like 4m, 1h, 15m, 1d, 30s
    try {
      if (s.endsWith("h")) return Double.parseDouble(s.substring(0, s.length() - 1)) * 3600.0;
      if (s.endsWith("m")) return Double.parseDouble(s.substring(0, s.length() - 1)) * 60.0;
      if (s.endsWith("s")) return Double.parseDouble(s.substring(0, s.length() - 1));
      if (s.endsWith("d")) return Double.parseDouble(s.substring(0, s.length() - 1)) * 86400.0;
      // fallback: parse as seconds
      return Double.parseDouble(s);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid interval string: " + s);
    }
  }
}
