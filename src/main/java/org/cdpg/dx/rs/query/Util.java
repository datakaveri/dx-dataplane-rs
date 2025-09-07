package org.cdpg.dx.rs.query;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
    if (params.get("type") != null) req.setType(Arrays.asList(params.get("type").split(",")));
    if (params.get("attrs") != null)
      req.setAttributes(Arrays.asList(params.get("attrs").split(",")));
    if (params.get("idPattern") != null)
      req.setIdPattern(Arrays.asList(params.get("idPattern").split(",")));

    // q, options, paging
    if (params.get("q") != null) req.setQ(params.get("q"));
    if (params.get("options") != null) req.setOptions(params.get("options"));
    if (params.get("from") != null) req.setFrom(params.get("from"));
    if (params.get("size") != null) req.setSize(params.get("size"));

    // temporal
    if (params.contains("timerel")
        || params.contains("time")
        || params.contains("endtime")
        || params.contains("timeProperty")) {
      TemporalQuery tq = new TemporalQuery();
      tq.setTimerel(params.get("timerel"));
      tq.setTime(params.get("time"));
      tq.setEndtime(params.get("endtime"));
      tq.setTimeProperty(params.get("timeProperty"));
      req.setTemporalQ(tq);
    }

    // geo
    if (params.contains("geometry")
        || params.contains("coordinates")
        || params.contains("geoproperty")
        || params.contains("georel")) {
      GeoQuery gq = new GeoQuery();
      gq.setGeometry(params.get("geometry"));
      String coords = params.get("coordinates");
      if (coords != null) {
        // attempts to parse comma separated doubles or JSON array like [x,y]
        String clean = coords.replaceAll("[\\[\\]\\s]", "");
        try {
          List<Double> lst =
              Arrays.stream(clean.split(",")).map(Double::parseDouble).collect(Collectors.toList());
          gq.setCoordinates(lst);
        } catch (Exception ignored) {
        }
      }
      gq.setGeoproperty(params.get("geoproperty"));

      if (params.get("georel") != null) {
        GeoRelation gr = new GeoRelation();
        String georel = params.get("georel");
        String[] parts = georel.split(";");
        gr.setRelation(parts[0]);
        if (parts.length == 2) {
          String[] kv = parts[1].split("=");
          try {
            if ("maxDistance".equalsIgnoreCase(kv[0])) gr.setMaxDistance(Double.parseDouble(kv[1]));
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
    if (body.containsKey("type")) {
      req.setType(List.of(body.getString("type")));
    }

    // attributes
    if (body.containsKey("attrs")) {
      req.setAttributes(Arrays.asList(body.getString("attrs").split(",")));
    }

    // q, options, paging
    if (body.containsKey("q")) req.setQ(body.getString("q"));
    if (body.containsKey("options")) req.setOptions(body.getString("options"));
    if (body.containsKey("from")) req.setFrom(body.getString("from"));
    if (body.containsKey("size")) req.setSize(body.getString("size"));

    // temporal
    if (body.containsKey("temporalQ")) {
      JsonObject t = body.getJsonObject("temporalQ");
      TemporalQuery tq = new TemporalQuery();
      tq.setTimerel(t.getString("timerel"));
      tq.setTime(t.getString("time"));
      tq.setEndtime(t.getString("endtime"));
      tq.setTimeProperty(t.getString("timeProperty"));
      req.setTemporalQ(tq);
    }

    // geo
    if (body.containsKey("geoQ")) {
      JsonObject g = body.getJsonObject("geoQ");
      GeoQuery gq = new GeoQuery();
      gq.setGeometry(g.getString("geometry"));
      if (g.containsKey("coordinates")) {
        JsonArray arr = g.getJsonArray("coordinates");
        List<Double> coords =
            arr.stream()
                .map(Object::toString)
                .map(Double::parseDouble)
                .collect(Collectors.toList());
        gq.setCoordinates(coords);
      }
      gq.setGeoproperty(g.getString("geoproperty"));

      if (g.containsKey("georel")) {
        GeoRelation gr = new GeoRelation();
        String georel = g.getString("georel");
        String[] parts = georel.split(";");
        gr.setRelation(parts[0]);
        if (parts.length == 2) {
          String[] kv = parts[1].split("=");
          try {
            if ("maxDistance".equalsIgnoreCase(kv[0])) gr.setMaxDistance(Double.parseDouble(kv[1]));
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
