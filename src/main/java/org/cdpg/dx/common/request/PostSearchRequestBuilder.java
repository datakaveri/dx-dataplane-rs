package org.cdpg.dx.common.request;

import static org.cdpg.dx.database.elastic.util.Constants.*;
import static org.cdpg.dx.database.elastic.util.Constants.FILTER;
import static org.cdpg.dx.database.elastic.util.Constants.Q_VALUE;
import static org.cdpg.dx.database.elastic.util.Constants.RESPONSE_FILTER;
import static org.cdpg.dx.database.elastic.util.Constants.SEARCH_CRITERIA_KEY;
import static org.cdpg.dx.database.elastic.util.Constants.SEARCH_TYPE_CRITERIA;
import static org.cdpg.dx.database.elastic.util.Constants.SEARCH_TYPE_TEXT;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.database.postgres.models.OrderBy;
import org.cdpg.dx.essearch.model.*;
import org.cdpg.dx.rs.latest.util.dtoUtil.GeoQ;

public class PostSearchRequestBuilder {
  private static final Logger LOGGER = LogManager.getLogger(PostSearchRequestBuilder.class);
  private static final Set<String> TEXT_FIELDS = Set.of(/* Add text fields here if any */ );
  private static final Set<String> TEMPORAL_SEARCH_TYPES =
      Set.of(BETWEEN_TEMPORAL, BEFORE_TEMPORAL, AFTER_TEMPORAL);
  private static final Set<String> RANGE_SEARCH_TYPES =
      Set.of(BETWEEN_RANGE, BEFORE_RANGE, AFTER_RANGE);
  private static final String ISO_EXAMPLE = "2021-09-10T00:00:00+05:30";
  private static final int ANY_NUMBER_OF_VALUES = -1;
  boolean isCountApi = false;
  boolean isAssetSearch = false;
  private RoutingContext routingContext;
  private String defaultSortBy = "observationDateTime";
  private String defaultOrder = "desc";

  public PostSearchRequestBuilder(RoutingContext routingContext) {
    this.routingContext = routingContext;
  }

  public static PostSearchRequestBuilder fromRoutingContext(RoutingContext routingContext) {
    return new PostSearchRequestBuilder(routingContext);
  }

  public PostSearchRequestBuilder setCountApi(boolean countApi) {
    isCountApi = countApi;
    return this;
  }

  public PostSearchRequestBuilder setAssetSearch(boolean assetSearch) {
    isAssetSearch = assetSearch;
    return this;
  }

  public SearchQuery build() {
    JsonObject requestBody = routingContext.getBodyAsJson();
    MultiMap params = routingContext.queryParams();
    return new SearchQuery(
        buildSearchType(requestBody),
        getSize(params),
        getPage(params),
        getId(requestBody),
        getFilters(requestBody),
        getTextSearchRequest(requestBody),
        getSearchCriteriaRequest(requestBody),
        getAccessPolicyRequest(isAssetSearch, getSub(routingContext)),
        getInstanceFilterRequest(requestBody),
        getResponseFilterRequest(requestBody),
        extractSortOrders(),
        getGeoQ(requestBody));
  }

  public int getSize(MultiMap params) {
    return params.get(SIZE_KEY) != null ? Integer.parseInt(params.get(SIZE_KEY)) : 100;
  }

  public int getPage(MultiMap params) {
    return params.get(PAGE_KEY) != null ? Integer.parseInt(params.get(PAGE_KEY)) : 1;
  }

  private String getSub(RoutingContext ctx) {
    try {
      if (ctx.user() != null) {
        return ctx.user().subject();
      }
    } catch (Exception e) {
      throw new DxBadRequestException("User subject not found in context", e);
    }
    return null;
  }

  private List<String> getFilters(JsonObject requestBody) {
    if (!requestBody.containsKey("filter")) {
      return new ArrayList<>();
    }
    JsonArray filterArray = requestBody.getJsonArray("filter");
    return filterArray.getList();
  }

  private String getId(JsonObject requestBody) {
    if (requestBody.containsKey("id")) {
      return requestBody.getString("id");
    }
    return null;
  }

  private String buildSearchType(JsonObject body) {
    LOGGER.debug("body received for building search type: " + body);
    boolean hasFilter = false;
    StringBuilder typeBuilder = new StringBuilder();

    if (body.getJsonArray(SEARCH_CRITERIA_KEY) != null
        && !body.getJsonArray(SEARCH_CRITERIA_KEY).isEmpty()) {
      typeBuilder.append(SEARCH_TYPE_CRITERIA);
      hasFilter = true;
    }
    if (body.getString(Q_VALUE) != null && !body.getString(Q_VALUE).isBlank()) {
      typeBuilder.append(SEARCH_TYPE_TEXT);
      hasFilter = true;
    }
    if (body.containsKey(FILTER)
        && body.getJsonArray(FILTER) != null
        && !body.getJsonArray(FILTER).isEmpty()) {
      typeBuilder.append(RESPONSE_FILTER);
      hasFilter = true;
    }
    if (body.getJsonObject(GEO_KEY_Q) != null && !body.getJsonObject(GEO_KEY_Q).isEmpty()) {
      typeBuilder.append(GEO_SEARCH_KEY);
      hasFilter = true;
    }
    if (!hasFilter) {
      throw new DxBadRequestException("Mandatory field(s) not provided");
    }
    return typeBuilder.toString();
  }

  private TextSearchRequest getTextSearchRequest(JsonObject requestBody) {
    String qValue = requestBody.getString(Q_VALUE);
    boolean fuzzy = requestBody.getBoolean("fuzzy", false);
    boolean autoComplete = requestBody.getBoolean("autoComplete", false);
    return new TextSearchRequest(qValue, fuzzy, autoComplete);
  }

  private SearchCriteriaRequest getSearchCriteriaRequest(JsonObject requestBody) {
    if (requestBody.containsKey(SEARCH_CRITERIA_KEY)) {
      JsonArray searchCriteriaArray = requestBody.getJsonArray(SEARCH_CRITERIA_KEY);
      if (searchCriteriaArray == null || searchCriteriaArray.isEmpty()) {
        throw new DxBadRequestException("Search criteria cannot be empty");
      }

      List<SearchCriteriaDTO> searchCriteria =
          searchCriteriaArray.stream()
              .map(
                  obj -> {
                    if (!(obj instanceof JsonObject criterion)) {
                      throw new DxBadRequestException("Each searchCriteria entry must be an object");
                    }
                    return SearchCriteriaDTO.fromJson(criterion);
                  })
              .toList();

      searchCriteria.forEach(this::validateSearchCriteria);

      List<String> filter =
          requestBody.getJsonArray("filter") != null
              ? requestBody.getJsonArray("filter").getList()
              : new ArrayList<>();

      return new SearchCriteriaRequest(searchCriteria, filter);
    }
    return null;
  }

  /**
   * Rejects malformed criteria here, before the query is handed to the search service. Elasticsearch
   * reports an unparseable value as an all-shards-failed error, which surfaces to the caller as a
   * 500, so anything we can check up front is checked up front.
   */
  private void validateSearchCriteria(SearchCriteriaDTO criterion) {
    String field = criterion.getField();
    List<Object> values = criterion.getValues();
    String searchType =
        criterion.getSearchType() == null || criterion.getSearchType().isBlank()
            ? TERM
            : criterion.getSearchType();

    if (field == null || field.isBlank()) {
      throw new DxBadRequestException("'field' is mandatory for every searchCriteria entry");
    }
    if (values == null || values.isEmpty()) {
      throw new DxBadRequestException("'values' is mandatory for searchCriteria on field " + field);
    }
    if (values.stream().anyMatch(Objects::isNull)) {
      throw new DxBadRequestException("'values' must not contain null for field " + field);
    }

    int expectedValues =
        switch (searchType) {
          case BETWEEN_TEMPORAL, BETWEEN_RANGE -> 2;
          case BEFORE_TEMPORAL, AFTER_TEMPORAL, BEFORE_RANGE, AFTER_RANGE -> 1;
          case TERM -> ANY_NUMBER_OF_VALUES;
          default -> throw new DxBadRequestException("Unsupported searchType: " + searchType);
        };
    if (expectedValues != ANY_NUMBER_OF_VALUES && values.size() != expectedValues) {
      throw new DxBadRequestException(
          "searchType "
              + searchType
              + " on field "
              + field
              + " expects exactly "
              + expectedValues
              + " value(s), but got "
              + values.size());
    }

    if (TEMPORAL_SEARCH_TYPES.contains(searchType)) {
      List<ZonedDateTime> timestamps =
          values.stream().map(value -> parseIsoDateTime(field, value)).toList();
      if (BETWEEN_TEMPORAL.equals(searchType) && timestamps.get(0).isAfter(timestamps.get(1))) {
        throw new DxBadRequestException(
            "Start time must not be after end time for field " + field);
      }
    } else if (RANGE_SEARCH_TYPES.contains(searchType)) {
      List<Double> numbers = values.stream().map(value -> parseNumber(field, value)).toList();
      if (BETWEEN_RANGE.equals(searchType) && numbers.get(0) > numbers.get(1)) {
        throw new DxBadRequestException(
            "Lower bound must not be greater than upper bound for field " + field);
      }
    }
  }

  /**
   * Parses the value the same way the GET temporal path does in {@code
   * ParamsValidator.parseIsoTime}, so both APIs accept the same timestamps. Note the UTC offset
   * needs a colon: +05:30, not +0530.
   */
  private ZonedDateTime parseIsoDateTime(String field, Object value) {
    String timestamp = value.toString().trim().replace(" ", "+");
    try {
      return ZonedDateTime.parse(timestamp);
    } catch (DateTimeParseException e) {
      try {
        return OffsetDateTime.parse(timestamp).toZonedDateTime();
      } catch (DateTimeParseException ex) {
        throw new DxBadRequestException(
            "Invalid ISO 8601 date-time '"
                + timestamp
                + "' for field "
                + field
                + "; expected for example "
                + ISO_EXAMPLE);
      }
    }
  }

  private double parseNumber(String field, Object value) {
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    try {
      return Double.parseDouble(value.toString().trim());
    } catch (NumberFormatException e) {
      throw new DxBadRequestException("Invalid numeric value '" + value + "' for field " + field);
    }
  }

  private AccessPolicyRequest getAccessPolicyRequest(boolean isAssetSearch, String sub) {
    return new AccessPolicyRequest(sub, isAssetSearch);
  }

  private InstanceFilterRequest getInstanceFilterRequest(JsonObject requestBody) {
    return new InstanceFilterRequest(requestBody.getString(INSTANCE));
  }

  private ResponseFilterRequest getResponseFilterRequest(JsonObject requestBody) {
    return new ResponseFilterRequest(
        buildSearchType(requestBody),
        isCountApi,
        requestBody.getJsonArray(ATTRIBUTE, new JsonArray()).getList(),
        requestBody.getJsonArray(FILTER, new JsonArray()).getList());
  }

  private List<OrderBy> extractSortOrders() {
    List<OrderBy> orderByList = new ArrayList<>();
    MultiMap params = routingContext.request().params(true);
    String sortParam = params.get("sort");
    final int MAX_SORT_FIELDS = 3;

    if (sortParam != null && !sortParam.isEmpty()) {
      String[] items = sortParam.split(";");
      if (items.length > MAX_SORT_FIELDS) {
        throw new DxBadRequestException("Too many sort fields. Max allowed is " + MAX_SORT_FIELDS);
      }

      for (String item : items) {
        String[] parts = item.split(":");
        if (parts.length != 2) {
          throw new DxBadRequestException(
              "Invalid sort format: " + item + ". Expected field:order");
        }

        String field = parts[0].trim();
        String direction = parts[1].trim().toLowerCase();
        if (!field.endsWith(KEYWORD_KEY)
            && (!field.equalsIgnoreCase(
                "observationDateTime") /*&& (!field.equalsIgnoreCase("actual_trip_start_time"))*/)) {
          field = field + KEYWORD_KEY;
        }
        if (!direction.equals("asc") && !direction.equals("desc")) {
          throw new DxBadRequestException("Invalid sort order: " + direction);
        }

        orderByList.add(new OrderBy(field, OrderBy.Direction.valueOf(direction.toUpperCase())));
      }
    }
    if (orderByList.isEmpty() && defaultSortBy != null) {
      orderByList.add(
          new OrderBy(defaultSortBy, OrderBy.Direction.valueOf(defaultOrder.toUpperCase())));
    }
    return orderByList;
  }

  private GeoQ getGeoQ(JsonObject requestBody) {
    if (requestBody.containsKey("geoQ")) {
      return new GeoQ(requestBody.getJsonObject("geoQ"));
    }
    return null;
  }
}
