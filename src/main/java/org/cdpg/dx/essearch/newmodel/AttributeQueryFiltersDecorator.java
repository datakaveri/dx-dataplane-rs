package org.cdpg.dx.essearch.newmodel;

import static org.cdpg.dx.database.elastic.util.Constants.*;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cdpg.dx.common.exception.DxEsException;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.util.QueryType;
import org.cdpg.dx.essearch.model.ElasticsearchQueryDecorator;
import org.cdpg.dx.essearch.model.FilterType;

public class AttributeQueryFiltersDecorator implements ElasticsearchQueryDecorator {
  private Map<FilterType, List<QueryModel>> queryFilters;
  private JsonObject requestQuery;

  public AttributeQueryFiltersDecorator(
      Map<FilterType, List<QueryModel>> queryFilters, JsonObject requestQuery) {
    this.queryFilters = queryFilters;
    this.requestQuery = requestQuery;
  }

  @Override
  public Map<FilterType, List<QueryModel>> add() {
    JsonArray attrQuery;

    if (requestQuery == null || !requestQuery.containsKey(ATTRIBUTE_QUERY_KEY)) {
      return queryFilters;
    }

    attrQuery = requestQuery.getJsonArray(ATTRIBUTE_QUERY_KEY);
    for (Object obj : attrQuery) {
      JsonObject attrObj = (JsonObject) obj;
      try {
        String attribute = attrObj.getString(ATTRIBUTE_KEY);
        String operator = attrObj.getString(OPERATOR);
        String attributeValue = attrObj.getString(VALUE);

        if (attribute == null || operator == null) {
          throw new DxEsException("Invalid attribute query: missing attribute or operator");
        }

        if (GREATER_THAN_OP.equalsIgnoreCase(operator)) {
          Map<String, Object> params = new HashMap<>();
          params.put(FIELD, attribute);
          params.put(GREATER_THAN, attributeValue);
          QueryModel range = new QueryModel(QueryType.RANGE).setQueryParameters(params);
          queryFilters.computeIfAbsent(FilterType.FILTER, k -> new ArrayList<>()).add(range);

        } else if (LESS_THAN_OP.equalsIgnoreCase(operator)) {
          Map<String, Object> params = new HashMap<>();
          params.put(FIELD, attribute);
          params.put(LESS_THAN, attributeValue);
          QueryModel range = new QueryModel(QueryType.RANGE).setQueryParameters(params);
          queryFilters.computeIfAbsent(FilterType.FILTER, k -> new ArrayList<>()).add(range);

        } else if (GREATER_THAN_EQ_OP.equalsIgnoreCase(operator)) {
          Map<String, Object> params = new HashMap<>();
          params.put(FIELD, attribute);
          params.put(GREATER_THAN_EQUALS, attributeValue);
          QueryModel range = new QueryModel(QueryType.RANGE).setQueryParameters(params);
          queryFilters.computeIfAbsent(FilterType.FILTER, k -> new ArrayList<>()).add(range);

        } else if (LESS_THAN_EQ_OP.equalsIgnoreCase(operator)) {
          Map<String, Object> params = new HashMap<>();
          params.put(FIELD, attribute);
          params.put(LESS_THAN_EQUALS, attributeValue);
          QueryModel range = new QueryModel(QueryType.RANGE).setQueryParameters(params);
          queryFilters.computeIfAbsent(FilterType.FILTER, k -> new ArrayList<>()).add(range);

        } else if (EQUAL_OP.equalsIgnoreCase(operator)) {
          Map<String, Object> params = new HashMap<>();
          params.put(FIELD, attribute);
          params.put(VALUE, attributeValue);
          QueryModel term = new QueryModel(QueryType.TERM).setQueryParameters(params);
          queryFilters.computeIfAbsent(FilterType.FILTER, k -> new ArrayList<>()).add(term);

        } else if (BETWEEN_OP.equalsIgnoreCase(operator)) {
          String lower = attrObj.getString(VALUE_LOWER);
          String upper = attrObj.getString(VALUE_UPPER);
          Map<String, Object> params = new HashMap<>();
          params.put(FIELD, attribute);
          params.put(GREATER_THAN_EQUALS, lower);
          params.put(LESS_THAN_EQUALS, upper);
          QueryModel range = new QueryModel(QueryType.RANGE).setQueryParameters(params);
          queryFilters.computeIfAbsent(FilterType.FILTER, k -> new ArrayList<>()).add(range);

        } else if (NOT_EQUAL_OP.equalsIgnoreCase(operator)) {
          Map<String, Object> params = new HashMap<>();
          params.put(FIELD, attribute);
          params.put(VALUE, attributeValue);
          QueryModel term = new QueryModel(QueryType.TERM).setQueryParameters(params);
          queryFilters.computeIfAbsent(FilterType.MUST_NOT, k -> new ArrayList<>()).add(term);

        } else {
          throw new DxEsException("invalid attribute operator");
        }
      } catch (DxEsException e) {
        throw e;
      } catch (Exception e) {
        throw new DxEsException("exception occured at decoding attributes", e);
      }
    }
    return queryFilters;
  }
}
