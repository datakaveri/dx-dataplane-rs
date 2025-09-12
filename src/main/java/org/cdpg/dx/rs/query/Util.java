package org.cdpg.dx.rs.query;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

public class Util {

  public static QueryRequest queryRequestFromParams(MultiMap params) {
    QueryRequest req = new QueryRequest();

    // ids
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
      req.setId(ids);
    }

    // simple string lists
    if (params.get(NGSILDQUERY_TYPE) != null)
      req.setType(Arrays.asList(params.get(NGSILDQUERY_TYPE).split(",")));
    if (params.get(NGSILDQUERY_ATTRIBUTE) != null)
      req.setAttributes(Arrays.asList(params.get(NGSILDQUERY_ATTRIBUTE).split(",")));
    if (params.get("idPattern") != null)
      req.setIdPattern(Arrays.asList(params.get("idPattern").split(",")));

    // q, options, paging
    if (params.get(NGSILDQUERY_Q) != null) req.setQ(params.get(NGSILDQUERY_Q));
    if (params.get(NGSILD_OPTIONS) != null) req.setOptions(params.get(NGSILD_OPTIONS));
    if (params.get(NGSILDQUERY_FROM) != null) req.setFrom(params.get(NGSILDQUERY_FROM));
    if (params.get(NGSILDQUERY_SIZE) != null) req.setSize(params.get(NGSILDQUERY_SIZE));

    // temporal
    if (params.contains(NGSILDQUERY_TIMEREL)
        || params.contains(NGSILDQUERY_TIMEAT)
        || params.contains(NGSILDQUERY_ENDTIMEAT)
        || params.contains(NGSILDQUERY_TIMEPROPERTY)) {
      TemporalQuery tq = new TemporalQuery();
      tq.setTimerel(params.get(NGSILDQUERY_TIMEREL));
      tq.setTime(params.get(NGSILDQUERY_TIMEAT));
      tq.setEndtime(params.get(NGSILDQUERY_ENDTIMEAT));
      tq.setTimeProperty(params.get(NGSILDQUERY_TIMEPROPERTY));
      req.setTemporalQ(tq);
    }

    // geo
    if (params.contains(NGSILDQUERY_GEOMETRY)
        || params.contains(NGSILDQUERY_COORDINATES)
        || params.contains(NGSILDQUERY_GEOPROPERTY)
        || params.contains(NGSILDQUERY_GEOREL)) {
      GeoQuery gq = new GeoQuery();
      gq.setGeometry(params.get(NGSILDQUERY_GEOMETRY));

      String coords = params.get(NGSILDQUERY_COORDINATES);
      if (coords != null) {
        try {
          // Try parsing as full JSON (nested allowed)
          JsonArray arr = new JsonArray(coords);
          gq.setCoordinates(arr);
        } catch (Exception e) {
          // fallback: simple comma-separated doubles
          String clean = coords.replaceAll("[\\[\\]\\s]", "");
          List<Double> lst =
              Arrays.stream(clean.split(",")).map(Double::parseDouble).collect(Collectors.toList());
          gq.setCoordinates(new JsonArray(lst));
        }
      }

      gq.setGeoproperty(params.get(NGSILDQUERY_GEOPROPERTY));

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
            else if ("minDistance".equalsIgnoreCase(kv[0]))
              gr.setMinDistance(Double.parseDouble(kv[1]));
          } catch (Exception ignored) {
          }
        }
        gq.setGeorel(gr);
      }

      req.setGeoQ(gq);
    }

    return req;
  }

  // new POST handler
  public static QueryRequest queryRequestFromBody(JsonObject body) {
    QueryRequest req = new QueryRequest();

    // ids
    if (body.containsKey("entities")) {
      List<URI> ids = new ArrayList<>();
      for (Object e : body.getJsonArray("entities")) {
        JsonObject entity = (JsonObject) e;
        if (entity.containsKey("id")) {
          try {
            ids.add(new URI(entity.getString("id")));
          } catch (URISyntaxException ignored) {
          }
        }
      }
      req.setId(ids);
    }

    // type
    if (body.containsKey(NGSILDQUERY_TYPE)) {
      req.setType(List.of(body.getString(NGSILDQUERY_TYPE)));
    }

    // attributes
    if (body.containsKey(NGSILDQUERY_ATTRIBUTE)) {
      req.setAttributes(Arrays.asList(body.getString(NGSILDQUERY_ATTRIBUTE).split(",")));
    }

    // q, options, paging
    if (body.containsKey(NGSILDQUERY_Q)) req.setQ(body.getString(NGSILDQUERY_Q));
    if (body.containsKey(NGSILD_OPTIONS)) req.setOptions(body.getString(NGSILD_OPTIONS));
    if (body.containsKey(NGSILDQUERY_FROM)) req.setFrom(body.getString(NGSILDQUERY_FROM));
    if (body.containsKey(NGSILDQUERY_SIZE)) req.setSize(body.getString(NGSILDQUERY_SIZE));

    // temporal
    if (body.containsKey("temporalQ")) {
      JsonObject t = body.getJsonObject("temporalQ");
      TemporalQuery tq = new TemporalQuery();
      tq.setTimerel(t.getString(NGSILDQUERY_TIMEREL));
      tq.setTime(t.getString(NGSILDQUERY_TIMEAT));
      tq.setEndtime(t.getString(NGSILDQUERY_ENDTIMEAT));
      tq.setTimeProperty(t.getString(NGSILDQUERY_TIMEPROPERTY));
      req.setTemporalQ(tq);
    }

    // geo
    if (body.containsKey(NGSILDQUERY_GEOQ)) {
      JsonObject g = body.getJsonObject(NGSILDQUERY_GEOQ);
      GeoQuery gq = new GeoQuery();
      gq.setGeometry(g.getString(NGSILDQUERY_GEOMETRY));

      if (g.containsKey(NGSILDQUERY_COORDINATES)) {
        // keep nested arrays intact
        gq.setCoordinates(g.getJsonArray(NGSILDQUERY_COORDINATES));
      }

      gq.setGeoproperty(g.getString(NGSILDQUERY_GEOPROPERTY));

      if (g.containsKey(NGSILDQUERY_GEOREL)) {
        GeoRelation gr = new GeoRelation();
        String georel = g.getString(NGSILDQUERY_GEOREL);
        String[] parts = georel.split(";");
        gr.setRelation(parts[0]);
        if (parts.length == 2) {
          String[] kv = parts[1].split("=");
          try {
            if (NGSILDQUERY_MAXDISTANCE.equalsIgnoreCase(kv[0]))
              gr.setMaxDistance(Double.parseDouble(kv[1]));
            else if ("minDistance".equalsIgnoreCase(kv[0]))
              gr.setMinDistance(Double.parseDouble(kv[1]));
          } catch (Exception ignored) {
          }
        }
        gq.setGeorel(gr);
      }

      req.setGeoQ(gq);
    }

    return req;
  }
}
