package org.cdpg.dx.rs.validation;

import io.vertx.core.*;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.rs.query.GeoRelation;
import org.cdpg.dx.rs.query.QueryRequest;
import org.cdpg.dx.rs.query.TemporalQuery;

/**
 * Semantic/business rule validation after QueryRequest is built. Includes async catalogue lookups
 * for applicable filters.
 */
public class QueryValidator {
  private static final Logger LOGGER = LogManager.getLogger(QueryValidator.class);

  // private final CacheService cacheService;
  private final int syncMaxDays;
  private final int asyncMaxDays;

  public QueryValidator(int syncMaxDays, int asyncMaxDays) {
    //  this.cacheService = cacheService;
    this.syncMaxDays = syncMaxDays;
    this.asyncMaxDays = asyncMaxDays;
  }

  public Future<Boolean> validate(QueryRequest request, boolean isAsync) {
    Promise<Boolean> promise = Promise.promise();

    // temporal window check
    if (request.getTemporalQ() != null && !isValidTemporalWindow(request.getTemporalQ(), isAsync)) {
      int limit = isAsync ? asyncMaxDays : syncMaxDays;
      promise.fail("Temporal window exceeds " + limit + " days");
      return promise.future();
    }

    // geo relation check
    if (request.getGeoQ() != null) {
      String geoError = validateGeoRelation(request.getGeoQ().getGeorel());
      if (geoError != null) {
        promise.fail(geoError);
        return promise.future();
      }
    }

    /*        // async applicable filter validation
    validateApplicableFilters(request)
            .onSuccess(v -> promise.complete(true))
            .onFailure(err -> promise.fail(err));*/

    return promise.future();
  }

  private boolean isValidTemporalWindow(TemporalQuery tq, boolean isAsync) {
    String timerel = tq.getTimerel();
    if (timerel == null) {
      return true; // nothing to validate
    }

    if (("during".equalsIgnoreCase(timerel) || "between".equalsIgnoreCase(timerel))
        && tq.getTime() != null
        && tq.getEndtime() != null) {
      try {
        ZonedDateTime start = ZonedDateTime.parse(tq.getTime());
        ZonedDateTime end = ZonedDateTime.parse(tq.getEndtime());
        long days = Duration.between(start, end).toDays();

        int limit = isAsync ? asyncMaxDays : syncMaxDays;
        return days <= limit;
      } catch (Exception e) {
        LOGGER.error("Failed to parse temporal query", e);
        return false;
      }
    }

    return true; // for before/after or unsupported values, nothing to enforce here
  }

  /** Validate semantic correctness of geo relation distances. */
  private String validateGeoRelation(GeoRelation geoRel) {
    if (geoRel == null) {
      return null;
    }

    Double max = geoRel.getMaxDistance();

    if (max != null) {
      if (max <= 0) {
        return "maxDistance must be greater than 0";
      }
      if (max >= 1000) {
        return "maxDistance must be less than 1000";
      }
    }

    return null; // valid
  }

  /** Ensure the resource supports the filters that the query requests. */
  /*private Future<Boolean> validateApplicableFilters(QueryRequest request) {
      Promise<Boolean> promise = Promise.promise();

      JsonObject req = new JsonObject()
              .put("type", CacheType.CATALOGUE_CACHE)
              .put("key", request.getIdAsString());

      cacheService.get(req).onComplete(ar -> {
          if (ar.failed()) {
              promise.fail("Failed to get filters for id " + request.getId());
              return;
          }

          Set<String> filters = new HashSet<>(
                  ar.result().getJsonArray("iudxResourceAPIs").getList()
          );
          LOGGER.debug("Applicable filters for {}: {}", request.getId().getFirst(), filters);

          // Check each filter against request
          if (request.getTemporalQ() != null && !filters.contains("TEMPORAL")) {
              promise.fail("Temporal parameters are not supported by this resource");
              return;
          }
          if (request.getGeoQ() != null && !filters.contains("SPATIAL")) {
              promise.fail("Spatial parameters are not supported by this resource");
              return;
          }
          if (request.getAttributes() != null && !filters.contains("ATTR")) {
              promise.fail("Attribute parameters are not supported by this resource");
              return;
          }

          promise.complete(true);
      });
      return promise.future();
  }*/
}
