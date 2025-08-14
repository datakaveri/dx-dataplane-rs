package org.cdpg.dx.essearch.model;

import static org.cdpg.dx.essearch.util.Constants.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxEsException;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.util.QueryType;

public class SearchCriteriaQueryDecorator implements ElasticsearchQueryDecorator {
  private static final Logger LOGGER = LogManager.getLogger(SearchCriteriaQueryDecorator.class);

  private final Map<FilterType, List<QueryModel>> queryMap;
  private final SearchCriteriaRequest request;

  public SearchCriteriaQueryDecorator(
      Map<FilterType, List<QueryModel>> queryMap, SearchCriteriaRequest request) {
    this.queryMap = queryMap;
    this.request = request;
  }

  @Override
  public Map<FilterType, List<QueryModel>> add() {
    if (request.getSearchCriteria() == null) {
      return queryMap;
    }
    List<SearchCriteriaDTO> criteria = request.getSearchCriteria();
    if (criteria == null || criteria.isEmpty()) {
      throw new DxEsException("Invalid Property Value: Empty searchCriteria");
    }

    List<QueryModel> mustList = new ArrayList<>();

    for (SearchCriteriaDTO criterion : criteria) {
      String field = criterion.getField();
      List<Object> values = criterion.getValues();
      String type = criterion.getSearchType() != null ? criterion.getSearchType() : TERM;
      LOGGER.info("Searchtype {}, field {}, values {}", type, field, values);

      switch (type) {
        case TERM:
          mustList.add(buildTermQuery(field, values));
          break;
        case BETWEEN_RANGE:
        case BETWEEN_TEMPORAL:
          if (values.size() != 2) {
            throw new DxEsException("Expected 2 values for between-type search");
          }
          mustList.add(
              buildRangeQuery(
                  field,
                  Map.of(
                      GREATER_THAN_EQUALS, values.get(0).toString(),
                      LESS_THAN_EQUALS, values.get(1).toString())));
          break;
        case BEFORE_RANGE:
        case BEFORE_TEMPORAL:
          mustList.add(buildRangeQuery(field, Map.of(LESS_THAN, values.get(0).toString())));
          break;
        case AFTER_RANGE:
        case AFTER_TEMPORAL:
          mustList.add(buildRangeQuery(field, Map.of(GREATER_THAN, values.get(0).toString())));
          break;
        default:
          throw new DxEsException("Unsupported searchType: " + type);
      }
    }

    // Fix: Add single must query directly to FILTER, avoid unnecessary bool nesting
    if (mustList.size() == 1) {
      queryMap.computeIfAbsent(FilterType.FILTER, k -> new ArrayList<>()).add(mustList.get(0));
    } else {
      queryMap.computeIfAbsent(FilterType.FILTER, k -> new ArrayList<>()).add(new QueryModel(QueryType.BOOL).setMustQueries(mustList));
    }
    LOGGER.info("queryMap {}", queryMap);
    if (queryMap.get(FilterType.FILTER) != null) {
      LOGGER.info("Size {}", queryMap.get(FilterType.FILTER).size());
      for (QueryModel queryModel : queryMap.get(FilterType.FILTER)) {
        LOGGER.info("query {}", queryModel.toJson());
      }
    }
    LOGGER.info("Done with query");
    return queryMap;
  }

  private QueryModel buildTermQuery(String field, List<Object> values) {
    if (values.size() == 1) {
      String value = values.get(0).toString();
      // If field is already keyword type, do not append .keyword
      if (DESCRIPTION_ATTR.equals(field) || field.startsWith(LOCATION)) {
        return new QueryModel(QueryType.MATCH).setQueryParameters(Map.of(FIELD, field, VALUE, value));
      } else if (TAGS.equals(field)) {
        return new QueryModel(QueryType.MATCH_PHRASE).setQueryParameters(Map.of(FIELD, field, VALUE, value));
      } else if (FILE_FORMAT.equals(field)) {
        return new QueryModel(QueryType.WILDCARD).setQueryParameters(Map.of(FIELD, field + KEYWORD_KEY, VALUE, value.toLowerCase(), CASE_INSENSITIVE, true));
      } else {
        // Only append .keyword if field is not already keyword type
        String searchField = field;
        return new QueryModel(QueryType.TERM).setQueryParameters(Map.of(FIELD, searchField, VALUE, value));
      }
    } else {
      List<QueryModel> shouldQueries = new ArrayList<>();
      for (Object valueObj : values) {
        String value = valueObj.toString();
        if (DESCRIPTION_ATTR.equals(field) || field.startsWith(LOCATION)) {
          shouldQueries.add(new QueryModel(QueryType.MATCH).setQueryParameters(Map.of(FIELD, field, VALUE, value)));
        } else if (TAGS.equals(field)) {
          shouldQueries.add(new QueryModel(QueryType.MATCH_PHRASE).setQueryParameters(Map.of(FIELD, field, VALUE, value)));
        } else if (FILE_FORMAT.equals(field)) {
          shouldQueries.add(new QueryModel(QueryType.WILDCARD).setQueryParameters(Map.of(FIELD, field + KEYWORD_KEY, VALUE, value.toLowerCase(), CASE_INSENSITIVE, true)));
        } else {
          String searchField = field;
          shouldQueries.add(new QueryModel(QueryType.TERM).setQueryParameters(Map.of(FIELD, searchField, VALUE, value)));
        }
      }
      QueryModel queryModel = new QueryModel(QueryType.BOOL);
      queryModel.setShouldQueries(shouldQueries);
      return queryModel;
    }
  }

  private QueryModel buildRangeQuery(String field, Map<String, String> operators) {
    Map<String, Object> rangeParams = new HashMap<>();
    rangeParams.put(FIELD, field);
    rangeParams.putAll(operators);
    return new QueryModel(QueryType.RANGE).setQueryParameters(rangeParams);
  }
}
