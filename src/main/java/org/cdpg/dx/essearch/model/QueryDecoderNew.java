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
      String[] qterms = ngsildQueryParams.getQ().split(";");
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

    // Optional: Add sorting if required
    /*Map<String, String> sortFields = new HashMap<>();
    sortFields.put(ngsildQueryParams.getTemporalQuery().getTimeproperty(), "desc");
    q.setSortFields(sortFields);
    LOGGER.debug("Sort fields set to: {}", sortFields);*/

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

    String[] attributes = queryTerms.split(";");
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
      String[] qterms = ngsildQueryParams.getQ().split(";");
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

    if (ngsildQueryParams.getPick() != null && !ngsildQueryParams.getPick().isEmpty()) {
      q.setIncludeFields(ngsildQueryParams.getPick());
      LOGGER.debug("Include fields set to: {}", ngsildQueryParams.getPick());
    }
    if (ngsildQueryParams.getOmit() != null && !ngsildQueryParams.getOmit().isEmpty()) {
      q.setExcludeFields(ngsildQueryParams.getOmit());
      LOGGER.debug("Exclude fields set to: {}", ngsildQueryParams.getOmit());
    }
    return q;
  }
}
