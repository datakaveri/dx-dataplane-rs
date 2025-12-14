package org.cdpg.dx.database.elastic.util;

import static org.cdpg.dx.database.elastic.util.Constants.FIELD;
import static org.cdpg.dx.database.elastic.util.Constants.SIZE_KEY;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import java.util.Map;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.database.elastic.model.QueryModel;

public class AggregationBuilder {
  // Factory method to create aggregations based on type
  public static Aggregation createAggregation(QueryModel queryModel) {
    return buildAggregation(queryModel).build();
  }

  // Recursive method to handle sub-aggregations
  private static Aggregation.Builder buildAggregation(QueryModel queryModel) {
    Aggregation.Builder builder = new Aggregation.Builder();
    AggregationType aggregationType = queryModel.getAggregationType();
    Map<String, Object> aggregationParameters = queryModel.getAggregationParameters();
    switch (aggregationType) {
      case TERMS:
        builder.terms(
            t -> {
              t.field((String) aggregationParameters.get(FIELD)); // Set the field

              // Check if SIZE_KEY is present and not null, then set the size
              if (aggregationParameters.containsKey(SIZE_KEY)
                  && aggregationParameters.get(SIZE_KEY) != null) {
                t.size((Integer) aggregationParameters.get(SIZE_KEY)); // Set the size
              }
              return t;
            });
        break;
      case HISTOGRAM:
        builder.histogram(
            h ->
                h.field((String) aggregationParameters.get(FIELD))
                    .interval(
                        // allow integer or double interval values
                        aggregationParameters.get("interval") instanceof Number
                            ? ((Number) aggregationParameters.get("interval")).doubleValue()
                            : null));
        break;
      case DATE_HISTOGRAM:
        // Use Elasticsearch date_histogram aggregation for time bucketing
        builder.dateHistogram(
            dh -> {
              dh.field((String) aggregationParameters.get(FIELD));
              // prefer calendar_interval or fixed_interval if provided
              if (aggregationParameters.containsKey("calendar_interval")
                  && aggregationParameters.get("calendar_interval") != null) {
                Object cal = aggregationParameters.get("calendar_interval");
                if (cal instanceof String) {
                  String s = ((String) cal).trim();
                  // If string contains digits (like "4m" or "1h"), use fixedInterval(Time)
                  if (s.matches(".*\\d.*")) {
                    dh.fixedInterval(Time.of(t -> t.time(s)));
                  } else {
                    // Map common names or single-letter codes to the CalendarInterval enum
                    CalendarInterval ci = parseCalendarInterval(s);
                    if (ci != null) {
                      dh.calendarInterval(ci);
                    } else {
                      // fallback to treating it as a time string for fixedInterval
                      dh.fixedInterval(Time.of(t -> t.time(s)));
                    }
                  }
                }
              } else if (aggregationParameters.containsKey("fixed_interval")
                  && aggregationParameters.get("fixed_interval") != null) {
                Object fixed = aggregationParameters.get("fixed_interval");
                if (fixed instanceof String) {
                  dh.fixedInterval(Time.of(t -> t.time((String) fixed)));
                } else if (fixed instanceof Number) {
                  long secs = Math.round(((Number) fixed).doubleValue());
                  dh.fixedInterval(Time.of(t -> t.time(secs + "s")));
                }
              } else if (aggregationParameters.containsKey("interval")
                  && aggregationParameters.get("interval") != null) {
                // If an interval in seconds was provided, convert to a seconds string
                // e.g., 240.0 -> "240s"
                Object iv = aggregationParameters.get("interval");
                if (iv instanceof Number) {
                  long secs = Math.round(((Number) iv).doubleValue());
                  dh.fixedInterval(Time.of(t -> t.time(secs + "s")));
                } else if (iv instanceof String) {
                  // allow string like "1d" or "1h"
                  dh.fixedInterval(Time.of(t -> t.time((String) iv)));
                }
              }
              return dh;
            });
        break;
      case AVG:
        builder.avg(a -> a.field((String) aggregationParameters.get(FIELD)));
        break;
      case MAX:
        builder.max(m -> m.field((String) aggregationParameters.get(FIELD)));
        break;
      case MIN:
        builder.min(m -> m.field((String) aggregationParameters.get(FIELD)));
        break;
      case SUM:
        builder.sum(s -> s.field((String) aggregationParameters.get(FIELD)));
        break;
      case CARDINALITY:
        builder.cardinality(c -> c.field((String) aggregationParameters.get(FIELD)));
        break;
      case VALUE_COUNT:
        builder.valueCount(vc -> vc.field((String) aggregationParameters.get(FIELD)));
        break;
      case EXTENDED_STATS:
        builder.extendedStats(e -> e.field((String) aggregationParameters.get(FIELD)));
        break;
      case FILTER:
        builder.filter(
            f ->
                f.term(
                    t -> {
                      t.field((String) aggregationParameters.get(FIELD));
                      Object val = aggregationParameters.get("value");
                      if (val != null) {
                        if (val instanceof Number) {
                          t.value(FieldValue.of(((Number) val).longValue()));
                        } else {
                          String sval = String.valueOf(val);
                          try {
                            long lv = Long.parseLong(sval);
                            t.value(FieldValue.of(lv));
                          } catch (NumberFormatException ex) {
                            t.value(FieldValue.of(sval));
                          }
                        }
                      }
                      return t;
                    }));
        break;
      case GLOBAL:
        builder.global(g -> g);
        break;

      default:
        throw new DxBadRequestException("Aggregation type not supported: " + aggregationType);
    }

    // Add sub-aggregations
    if (queryModel.getAggregationsMap() != null) {
      queryModel
          .getAggregationsMap()
          .forEach(
              (name, subQueryModel) ->
                  builder.aggregations(name, buildAggregation(subQueryModel).build()));
    }

    return builder;
  }

  // Try to parse a simple calendar interval string into the CalendarInterval enum.
  private static CalendarInterval parseCalendarInterval(String s) {
    if (s == null || s.isEmpty()) return null;
    // Preserve original for single-letter case-sensitivity (e.g., 'M' = Month)
    String orig = s.trim();
    if (orig.equals("M")) {
      return CalendarInterval.Month;
    }
    String low = orig.toLowerCase();
    switch (low) {
      case "s":
      case "sec":
      case "second":
      case "seconds":
        return CalendarInterval.Second;
      case "m":
      case "min":
      case "minute":
      case "minutes":
        return CalendarInterval.Minute;
      case "h":
      case "hour":
      case "hours":
      case "hr":
        return CalendarInterval.Hour;
      case "d":
      case "day":
      case "days":
        return CalendarInterval.Day;
      case "w":
      case "week":
      case "weeks":
        return CalendarInterval.Week;
      case "month":
      case "months":
        return CalendarInterval.Month;
      case "q":
      case "quarter":
      case "quarters":
        return CalendarInterval.Quarter;
      case "y":
      case "year":
      case "years":
        return CalendarInterval.Year;
      default:
        // Also try to match enum name directly (case-insensitive)
        try {
          return CalendarInterval.valueOf(orig.substring(0, 1).toUpperCase() + orig.substring(1));
        } catch (Exception ex) {
          return null;
        }
    }
  }
}
