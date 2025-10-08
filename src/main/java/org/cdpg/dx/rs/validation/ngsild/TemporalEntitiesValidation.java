package org.cdpg.dx.rs.validation.ngsild;

import static org.cdpg.dx.rs.ngsild.util.NGSILDConstant.*;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.rs.ngsild.searchmodels.*;
import org.cdpg.dx.rs.ngsild.temporal.model.TemporalGetRequest;

public class TemporalEntitiesValidation {
  private static final Logger LOGGER = LogManager.getLogger(TemporalEntitiesValidation.class);

  private MultiMap params;

  public TemporalEntitiesValidation() {}

  public static TemporalGetRequest validateParam(MultiMap params) {
    TemporalGetRequest temporalGetRequest = new TemporalGetRequest();
    String idVal = params.get("id");
    if (idVal != null) {
      List<URI> ids =
          Arrays.stream(idVal.split(","))
              .map(
                  s -> {
                    try {
                      return new URI(s.trim());
                    } catch (URISyntaxException e) {
                      return null;
                    }
                  })
              .filter(Objects::nonNull)
              .collect(Collectors.toList());
      temporalGetRequest.setId(ids);
    }

    // simple string lists
    if (params.get(NGSILDQUERY_OMIT) != null)
      temporalGetRequest.setOmit(Arrays.asList(params.get(NGSILDQUERY_OMIT).split(",")));
    if (params.get(NGSILDQUERY_PICK) != null)
      temporalGetRequest.setPick(Arrays.asList(params.get(NGSILDQUERY_PICK).split(",")));

    // q, options, paging
    if (params.get(NGSILDQUERY_Q) != null) temporalGetRequest.setQ(params.get(NGSILDQUERY_Q));
    if (params.get(NGSILD_OPTIONS) != null)
      temporalGetRequest.setOptions(params.get(NGSILD_OPTIONS));
    if (params.get(NGSILDQUERY_FROM) != null)
      temporalGetRequest.setFrom(params.get(NGSILDQUERY_FROM));
    if (params.get(NGSILDQUERY_SIZE) != null)
      temporalGetRequest.setSize(params.get(NGSILDQUERY_SIZE));

    // temporal
    if (params.contains(NGSILDQUERY_TIMEREL)
        || params.contains(NGSILDQUERY_TIMEAT)
        || params.contains(NGSILDQUERY_ENDTIMEAT)
        || params.contains(NGSILDQUERY_TIMEPROPERTY)) {
      TemporalQuery tq = new TemporalQuery();
      tq.setTimerel(params.get(NGSILDQUERY_TIMEREL));
      tq.setTimeAt(params.get(NGSILDQUERY_TIMEAT));
      tq.setEndtimeAt(params.get(NGSILDQUERY_ENDTIMEAT));
      tq.setTimeproperty(params.get(NGSILDQUERY_TIMEPROPERTY));
      temporalGetRequest.setTemporalQ(tq);
    }

    // geo
    if (params.contains(NGSILDQUERY_GEOMETRY)
        || params.contains(NGSILDQUERY_COORDINATES)
        || params.contains(NGSILDQUERY_GEOPROPERTY)
        || params.contains(NGSILDQUERY_GEOREL)) {
      GeoQuery geoQuery = new GeoQuery();
      geoQuery.setGeometry(params.get(NGSILDQUERY_GEOMETRY));

      String coords = params.get(NGSILDQUERY_COORDINATES);
      if (coords != null) {
        try {
          // Try parsing as full JSON (nested allowed)
          JsonArray arr = new JsonArray(coords);
          geoQuery.setCoordinates(arr);
        } catch (Exception e) {
          // fallback: simple comma-separated doubles
          String clean = coords.replaceAll("[\\[\\]\\s]", "");
          List<Double> lst =
              Arrays.stream(clean.split(",")).map(Double::parseDouble).collect(Collectors.toList());
          geoQuery.setCoordinates(new JsonArray(lst));
        }
      }

      geoQuery.setGeoproperty(params.get(NGSILDQUERY_GEOPROPERTY));

      if (params.get(NGSILDQUERY_GEOREL) != null) {
        GeoRelation gr = new GeoRelation();
        String geoRel = params.get(NGSILDQUERY_GEOREL);
        String[] parts = geoRel.split(";");
        gr.setRelation(parts[0]);
        if (parts.length == 2) {
          String[] kv = parts[1].split("=");
          try {
            if (NGSILDQUERY_MAXDISTANCE.equalsIgnoreCase(kv[0]))
              gr.setMaxDistance(Double.parseDouble(kv[1]));
          } catch (Exception ignored) {
          }
        }
        geoQuery.setGeorel(gr);
      }

      temporalGetRequest.setGeoQ(geoQuery);
    }
    String sortBy;
    if (params.get("sort") != null) {
      sortBy = params.get("sort");
    } else {
      sortBy = "observationDateTime:desc";
    }

    String sortOrder = sortBy.split(":")[1];
    sortBy = sortBy.split(":")[0];

    temporalGetRequest.setSortBy(sortBy);
    temporalGetRequest.setSortOrder(sortOrder);

    return temporalGetRequest;
  }
}
