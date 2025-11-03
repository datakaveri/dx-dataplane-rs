package org.cdpg.dx.essearch.newmodel;

import static org.cdpg.dx.database.elastic.util.Constants.FIELD;
import static org.cdpg.dx.essearch.util.Constants.*;
import static org.cdpg.dx.essearch.util.Constants.AFTER;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.util.QueryType;
import org.cdpg.dx.essearch.model.ElasticsearchQueryDecorator;
import org.cdpg.dx.essearch.model.FilterType;
import org.cdpg.dx.rs.ngsild.searchmodels.TemporalQuery;

public class TemporalQueryFiltersDecorator implements ElasticsearchQueryDecorator {
  private final int defaultDateLimit;
  private Map<FilterType, List<QueryModel>> queryMaps;
  private TemporalQuery temporalQuery;

  public TemporalQueryFiltersDecorator(
      Map<FilterType, List<QueryModel>> queryMaps,
      TemporalQuery requestTemporalQuery,
      int defaultDateLimit) {
    this.queryMaps = queryMaps;
    this.temporalQuery = requestTemporalQuery;
    this.defaultDateLimit = defaultDateLimit;
  }

  @Override
  public Map<FilterType, List<QueryModel>> add() {

    if (temporalQuery == null) {
      return null;
    }

    String timeProperty =
        temporalQuery.getTimeproperty() != null
            ? temporalQuery.getTimeproperty()
            : "observationDateTime"; // Default temporal property

    String timerel = temporalQuery.getTimerel();
    String timeAt = temporalQuery.getTimeAt();
    String endtimeAt = temporalQuery.getEndtimeAt();

    ZonedDateTime startDateTime = getZonedDateTime(timeAt);
    ZonedDateTime endDateTime = (endtimeAt != null) ? getZonedDateTime(endtimeAt) : null;

    if (BETWEEN.equalsIgnoreCase(timerel)) {
      validateTemporalPeriod(startDateTime, endDateTime);
    } else if (BEFORE.equalsIgnoreCase(timerel)) {
      timeAt = startDateTime.minusDays(defaultDateLimit).toString();
      endtimeAt = startDateTime.toString();
    } else if (AFTER.equalsIgnoreCase(timerel)) {
      timeAt = startDateTime.toString();
      endtimeAt = getEndDateForAfterQuery(startDateTime);
    } else {
      throw new DxBadRequestException("exception while parsing date/time");
    }

    final String startTime = timeAt;
    final String endTime = endtimeAt;

    QueryModel temporalQueryModel = new QueryModel(QueryType.RANGE);
    Map<String, Object> queryParams = new HashMap<String, Object>();
    queryParams.put(FIELD, timeProperty);
    queryParams.put(LESS_THAN_EQUALS, endTime);
    queryParams.put(GREATER_THAN_EQUALS, startTime);
    temporalQueryModel.setQueryParameters(queryParams);

    List<QueryModel> queryList = queryMaps.get(FilterType.FILTER);
    queryList.add(temporalQueryModel);
    return queryMaps;
  }

  private void validateTemporalPeriod(ZonedDateTime startDateTime, ZonedDateTime endDateTime) {
    if (endDateTime == null) {
      throw new DxBadRequestException("No endDate[required mandatory field] provided for query");
    }

    if (startDateTime.isAfter(endDateTime)) {
      throw new DxBadRequestException("end date is before start date");
    }
  }

  private ZonedDateTime getZonedDateTime(String time) {
    try {
      return ZonedDateTime.parse(time);
    } catch (DateTimeParseException e) {
      throw new DxBadRequestException("exception while parsing date/time");
    }
  }

  private String getEndDateForAfterQuery(ZonedDateTime startDateTime) {
    ZonedDateTime endDateTime;
    endDateTime = startDateTime.plusDays(defaultDateLimit);
    ZonedDateTime now = ZonedDateTime.now();
    long difference = endDateTime.compareTo(now);
    if (difference > 0) {
      return now.toString();
    } else {
      return endDateTime.toString();
    }
  }
}
