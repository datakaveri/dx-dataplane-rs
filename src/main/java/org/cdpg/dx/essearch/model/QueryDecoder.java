package org.cdpg.dx.database.elastic.model;

import static org.cdpg.dx.database.elastic.util.Constants.*;

import java.util.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxEsException;
import org.cdpg.dx.database.elastic.util.AggregationType;
import org.cdpg.dx.database.elastic.util.QueryType;
import org.cdpg.dx.essearch.model.*;

public class QueryDecoder {
  private static final Logger LOGGER = LogManager.getLogger(QueryDecoder.class);

  public QueryModel getQueryModel(SearchQuery request) {
    String searchType = request.getSearchType();
    boolean isValidQuery = false;

    if ("getParentObjectInfo".equalsIgnoreCase(searchType)) {
      LOGGER.info("getParentObjectInfo query");
      return buildGetParentObjectInfoQuery(request);
    }

    Map<FilterType, List<QueryModel>> queryMap = new HashMap<>();
    for (FilterType filterType : FilterType.values()) {
      queryMap.put(filterType, new ArrayList<>());
    }

    if (searchType != null && searchType.matches(SEARCH_CRITERIA_REGEX)) {
      LOGGER.debug("Info: searchCriteria block");
      new SearchCriteriaQueryDecorator(queryMap, request.getSearchCriteriaRequest()).add();
      isValidQuery = true;
    }

    if (searchType != null && searchType.matches(TEXTSEARCH_REGEX)) {
      LOGGER.debug("Info: Text search block");
      new TextSearchQueryDecorator(queryMap, request.getTextSearchRequest()).add();
      isValidQuery = true;
    }

    new AccessPolicyQueryDecorator(queryMap, request.getAccessPolicyRequest()).add();
    QueryModel excludeDatabankFalse = buildUploadStatusExclusion(ITEM_TYPE_DATA_BANK);
    QueryModel excludeAiModelFalse = buildUploadStatusExclusion(ITEM_TYPE_AI_MODEL);
    queryMap.get(FilterType.MUST_NOT).add(excludeDatabankFalse);
    queryMap.get(FilterType.MUST_NOT).add(excludeAiModelFalse);
    if (searchType != null && searchType.matches(RESPONSE_FILTER_REGEX)) {
      new ResponseFilterDecorator(queryMap, request.getResponseFilterRequest()).add();
      isValidQuery = true;
    }

    if (!isValidQuery) {
      throw new DxEsException("Invalid search query");
    }

    QueryModel q = new QueryModel();
    q.setQueries(getBoolQuery(queryMap));
    // Optional pagination support
    if (request.getSize() != null) {
      int size = request.getSize();
      q.setLimit(String.valueOf(size));
      if (request.getPage() != null) {
        int offset = (request.getPage() - 1) * size;
        q.setOffset(String.valueOf(offset));
      }
      return q;
    }

    for (QueryModel qm : queryMap.get(FilterType.FILTER)) {
      if (qm.getIncludeFields() != null) {
        q.setIncludeFields(qm.getIncludeFields());
      }
    }

    return q;
  }

  public QueryModel listMultipleItemTypesQuery(SearchQuery request) {
    LOGGER.debug("listMultipleItemTypesQuery - {}", request);
    Map<FilterType, List<QueryModel>> queryMap = new HashMap<>();
    for (FilterType filterType : FilterType.values()) {
      queryMap.put(filterType, new ArrayList<>());
    }

    new AccessPolicyQueryDecorator(queryMap, request.getAccessPolicyRequest()).add();
    new SearchCriteriaQueryDecorator(queryMap, request.getSearchCriteriaRequest()).add();
    new InstanceFilterQueryDecorator(queryMap, request.getInstanceFilterRequest()).add();

    QueryModel excludeDatabankFalse = buildUploadStatusExclusion(ITEM_TYPE_DATA_BANK);
    QueryModel excludeAiModelFalse = buildUploadStatusExclusion(ITEM_TYPE_AI_MODEL);
    queryMap.get(FilterType.MUST_NOT).add(excludeDatabankFalse);
    queryMap.get(FilterType.MUST_NOT).add(excludeAiModelFalse);
    QueryModel finalQuery = new QueryModel();
    finalQuery.setQueries(getBoolQuery(queryMap));

    List<String> filters = request.getFilter();
    int size =
        request.getSize() != null
            ? request.getSize()
            : FILTER_PAGINATION_SIZE - (request.getPage() != null ? request.getPage() : 1);
    List<QueryModel> aggs = new ArrayList<>();

    if (filters != null) {
      for (String filter : filters) {
        Map<String, Object> aggParams = Map.of(FIELD, filter + KEYWORD_KEY, SIZE_KEY, size);
        QueryModel agg = new QueryModel();
        agg.setAggregationType(AggregationType.TERMS);
        agg.setAggregationName(filter);
        agg.setAggregationParameters(aggParams);
        aggs.add(agg);
      }
      finalQuery.setAggregations(aggs);
    }

    if (request.getPage() != null) {
      finalQuery.setLimit(String.valueOf(size));
    }

    return finalQuery;
  }

  private QueryModel buildUploadStatusExclusion(String itemType) {
    return new QueryModel(QueryType.BOOL)
        .setMustQueries(
            List.of(
                new QueryModel(QueryType.TERM)
                    .setQueryParameters(
                        Map.of(
                            FIELD, TYPE_KEYWORD,
                            VALUE, itemType)),
                new QueryModel(QueryType.TERM)
                    .setQueryParameters(Map.of(FIELD, DATA_UPLOAD_STATUS, VALUE, false))));
  }

  private QueryModel buildGetParentObjectInfoQuery(SearchQuery request) {
    String id = request.getId();
    String[] fields = {
      "type",
      "provider",
      "ownerUserId",
      "resourceGroup",
      "name",
      "organizationId",
      "shortDescription",
      "resourceServer",
      "resourceServerRegURL",
      "cos",
      "cos_admin"
    };
    List<QueryModel> mustQueries =
        List.of(
            new QueryModel(QueryType.TERM)
                .setQueryParameters(Map.of(FIELD, ID_KEYWORD, VALUE, id)));
    QueryModel boolQuery = new QueryModel(QueryType.BOOL);
    boolQuery.setMustQueries(mustQueries);
    boolQuery.setIncludeFields(Arrays.asList(fields));
    return boolQuery;
  }

  public QueryModel buildGetItemWithNameExistsQuery(String type, String name) {
    QueryModel typeMatchQuery = new QueryModel(QueryType.MATCH);
    typeMatchQuery.setQueryParameters(Map.of(FIELD, TYPE_KEY, VALUE, type));
    QueryModel nameMatchQuery = new QueryModel(QueryType.MATCH);
    nameMatchQuery.setQueryParameters(Map.of(FIELD, NAME + KEYWORD_KEY, VALUE, name));

    QueryModel boolQuery = new QueryModel(QueryType.BOOL);
    boolQuery.setMustQueries(List.of(typeMatchQuery, nameMatchQuery));
    return boolQuery;
  }

  public QueryModel getItemQueryModel(String id) {

    QueryModel q = new QueryModel();

    QueryModel boolQuery = new QueryModel(QueryType.BOOL);
    QueryModel idTermQuery = new QueryModel(QueryType.TERM);
    idTermQuery.setQueryParameters(Map.of(FIELD, ID_KEYWORD, VALUE, id));

    boolQuery.setMustQueries(List.of(idTermQuery));
    q.setQueries(boolQuery);
    return q;
  }

  private QueryModel getBoolQuery(Map<FilterType, List<QueryModel>> filterQueries) {
    QueryModel boolQuery = new QueryModel(QueryType.BOOL);
    List<QueryModel> mustQueries = filterQueries.getOrDefault(FilterType.MUST, Collections.emptyList());
    List<QueryModel> filterQueriesList = filterQueries.getOrDefault(FilterType.FILTER, Collections.emptyList());
    List<QueryModel> mustNotQueries = filterQueries.getOrDefault(FilterType.MUST_NOT, Collections.emptyList());
    List<QueryModel> shouldQueries = filterQueries.getOrDefault(FilterType.SHOULD, Collections.emptyList());

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

  public QueryModel setCountAggregations() {
    QueryModel agg = new QueryModel();
    agg.setAggregationType(AggregationType.TERMS);
    agg.setAggregationName(RESULTS);
    Map<String, Object> aggParams = Map.of(FIELD, TYPE_KEYWORD);
    agg.setAggregationParameters(aggParams);
    return agg;
  }

  // Added for handling queries based on observation date time
  public QueryModel getQueryBasedOnObservationDateTime(int size, int page) {
    QueryModel q = new QueryModel();
    q.setSortFields(Map.of("observationDateTime", "desc"));
    q.setLimit(String.valueOf(size));
    int offset = (page - 1) * size;
    q.setOffset(String.valueOf(offset));
    return q;
  }

  // Added for temporal query based on observation date time
  public QueryModel getTemporalQueryBasedOnObservationDateTime(
      TemporalQueryRequestModel temporalQueryRequest) {
    LOGGER.debug("into query decoder getTemporalQueryBasedOnObservationDateTime()");
    if (temporalQueryRequest.getTimeRel() == null || temporalQueryRequest.getTimeRel().isEmpty()) {
      throw new DxEsException("Time relation is required for temporal queries");
    } else {
      Map<FilterType, List<QueryModel>> queryMap = new HashMap<>();
      for (FilterType filterType : FilterType.values()) {
        queryMap.put(filterType, new ArrayList<>());
      }
      int defaultDateLimit = 0;
      if (temporalQueryRequest.getTimeRel() != null && temporalQueryRequest.getTime() != null) {
        defaultDateLimit = Integer.parseInt(temporalQueryRequest.getTimeLimit().split(",")[2]);
      }
      new TemporalQueryFiltersDecorator(queryMap, temporalQueryRequest, defaultDateLimit).add();

      QueryModel q = new QueryModel();
      q.setLimit(String.valueOf(temporalQueryRequest.getSize()));
      int offset = (temporalQueryRequest.getPage() - 1) * temporalQueryRequest.getSize();
      q.setOffset(String.valueOf(offset));
      q.setQueries(getBoolQuery(queryMap));

      for (QueryModel qm : queryMap.get(FilterType.FILTER)) {
        if (qm.getIncludeFields() != null) {
          q.setIncludeFields(qm.getIncludeFields());
        }
      }
      return q;
    }
  }

  // Added for creating simple search criteria query
  public QueryModel getSearchQueryModel(SearchQuery request) {
    String searchType = request.getSearchType();
    boolean isValidQuery = false;

    if ("getParentObjectInfo".equalsIgnoreCase(searchType)) {
      LOGGER.info("getParentObjectInfo query");
      return buildGetParentObjectInfoQuery(request);
    }

    Map<FilterType, List<QueryModel>> queryMap = new HashMap<>();
    for (FilterType filterType : FilterType.values()) {
      queryMap.put(filterType, new ArrayList<>());
    }

    if (searchType != null && searchType.matches(SEARCH_CRITERIA_REGEX)) {
      LOGGER.debug("Info: searchCriteria block");
      new SearchCriteriaQueryDecorator(queryMap, request.getSearchCriteriaRequest()).add();
      isValidQuery = true;
    }

    if (searchType != null && searchType.matches(TEXTSEARCH_REGEX)) {
      LOGGER.debug("Info: Text search block");
      new TextSearchQueryDecorator(queryMap, request.getTextSearchRequest()).add();
      isValidQuery = true;
    }

    if (searchType != null && searchType.matches(RESPONSE_FILTER_REGEX)) {
        LOGGER.debug("Info: Response filter block");
      new ResponseFilterDecorator(queryMap, request.getResponseFilterRequest()).add();
      isValidQuery = true;
    }

    if (searchType!= null && searchType.matches(ACCESS_POLICY_REGEX)){
        LOGGER.debug("Info: Access policy block");
        new AccessPolicyQueryDecorator(queryMap, request.getAccessPolicyRequest()).add();
        isValidQuery = true;
    }
    if (!isValidQuery) {
      throw new DxEsException("Invalid search query");
    }

    QueryModel q = new QueryModel();
    q.setQueries(getBoolQuery(queryMap));
    // Optional pagination support
    if (request.getSize() != null) {
      int size = request.getSize();
      q.setLimit(String.valueOf(size));
      if (request.getPage() != null) {
        int offset = (request.getPage() - 1) * size;
        q.setOffset(String.valueOf(offset));
      }
      // Set includeFields from filter if present
      if (request.getFilter() != null && !request.getFilter().isEmpty()) {
        q.setIncludeFields(request.getFilter());
      } else {
        for (QueryModel qm : queryMap.get(FilterType.FILTER)) {
          if (qm.getIncludeFields() != null) {
            q.setIncludeFields(qm.getIncludeFields());
          }
        }
      }
      return q;
    }

    return q;
  }
}
