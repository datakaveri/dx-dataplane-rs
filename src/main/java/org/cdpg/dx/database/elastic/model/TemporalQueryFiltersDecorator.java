package org.cdpg.dx.database.elastic.model;

import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.database.elastic.util.QueryType;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.cdpg.dx.database.elastic.util.Constants.*;

public class TemporalQueryFiltersDecorator implements ElasticsearchQueryDecorator {
    private final int defaultDateLimit;
    private Map<FilterType, List<QueryModel>> queryMaps;
    private TemporalQueryRequestModel requestQuery;

    public TemporalQueryFiltersDecorator(
            Map<FilterType, List<QueryModel>> queryMaps, TemporalQueryRequestModel requestQuery, int defaultDateLimit) {
        this.queryMaps = queryMaps;
        this.requestQuery = requestQuery;
        this.defaultDateLimit = defaultDateLimit;
    }

    @Override
    public Map<FilterType, List<QueryModel>> add() {
        String queryRequestTimeRelation = requestQuery.getTimeRel();
        String queryRequestStartTime = requestQuery.getTime();
        String queryRequestEndTime = requestQuery.getEndTime();
    System.out.println(queryRequestEndTime + " " + queryRequestStartTime + " " + queryRequestTimeRelation);
        ZonedDateTime startDateTime = getZonedDateTime(queryRequestStartTime);
        ZonedDateTime endDateTime =
                (queryRequestEndTime != null) ? getZonedDateTime(queryRequestEndTime) : null;

        if (DURING.equalsIgnoreCase(queryRequestTimeRelation)
                || BETWEEN.equalsIgnoreCase(queryRequestTimeRelation)) {
            validateTemporalPeriod(startDateTime, endDateTime);
        } else if (BEFORE.equalsIgnoreCase(queryRequestTimeRelation)) {
            queryRequestStartTime = startDateTime.minusDays(defaultDateLimit).toString();
            queryRequestEndTime = startDateTime.toString();
        } else if (AFTER.equalsIgnoreCase(queryRequestTimeRelation)) {
            queryRequestStartTime = startDateTime.toString();
            queryRequestEndTime = getEndDateForAfterQuery(startDateTime);
        } else {
            throw new DxBadRequestException("exception while parsing date/time");
        }

        final String startTime = queryRequestStartTime;
        final String endTime = queryRequestEndTime;

        QueryModel temporalQuery = new QueryModel(QueryType.RANGE);
        Map<String, Object> queryParams = new HashMap<String, Object>();
        queryParams.put(FIELD, "observationDateTime");
        queryParams.put(LESS_THAN_EQUALS, endTime);
        queryParams.put(GREATER_THAN_EQUALS, startTime);
        temporalQuery.setQueryParameters(queryParams);

        List<QueryModel> queryList = queryMaps.get(FilterType.FILTER);
        queryList.add(temporalQuery);
        return queryMaps;
    }

    public void addDefaultTemporalFilters(Map<FilterType, List<QueryModel>> queryLists,
                                          TemporalQueryRequestModel query) {
        String[] timeLimitConfig = query.getTimeLimit().split(",");
        String deploymentType = timeLimitConfig[0];
        String dateToUseForDevDeployment = timeLimitConfig[1];
        if (PROD_INSTANCE.equalsIgnoreCase(deploymentType)) {
            addDefaultForProduction(queryLists);
        } else if (TEST_INSTANCE.equalsIgnoreCase(deploymentType)) {
            addDefaultForDev(queryLists, dateToUseForDevDeployment);
        } else {
            throw new DxBadRequestException("invalid timeLimit config passed");
        }
    }

    private void addDefaultForDev(
            Map<FilterType, List<QueryModel>> queryLists, String dateToUseForDevDeployment) {
        ZonedDateTime endTime = getZonedDateTime(dateToUseForDevDeployment);
        ZonedDateTime startTime = endTime.minusDays(defaultDateLimit);
        QueryModel temporalQuery = new QueryModel(QueryType.RANGE);
        Map<String, Object> queryParams = new HashMap<String, Object>();
        queryParams.put(FIELD, "observationDateTime");
        queryParams.put(LESS_THAN_EQUALS, endTime.toString());
        queryParams.put(GREATER_THAN_EQUALS, startTime.toString());
        temporalQuery.setQueryParameters(queryParams);

        List<QueryModel> queryList = queryLists.get(FilterType.FILTER);
        queryList.add(temporalQuery);
    }

    private void addDefaultForProduction(Map<FilterType, List<QueryModel>> queryLists) {
        OffsetDateTime currentDateTime = OffsetDateTime.now().minusDays(defaultDateLimit);
        QueryModel temporalQuery = new QueryModel(QueryType.RANGE);
        Map<String, Object> queryParams = new HashMap<String, Object>();
        queryParams.put(FIELD, "observationDateTime");
        queryParams.put(GREATER_THAN_EQUALS, currentDateTime.toString());
        temporalQuery.setQueryParameters(queryParams);

        List<QueryModel> queryList = queryLists.get(FilterType.FILTER);
        queryList.add(temporalQuery);
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
}
