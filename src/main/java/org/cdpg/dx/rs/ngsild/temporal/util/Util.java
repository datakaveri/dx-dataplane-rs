package org.cdpg.dx.rs.ngsild.temporal.util;

import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class Util {
  public static MultiMap convertBodyToParams(JsonObject requestJson) {
    MultiMap paramsMap = MultiMap.caseInsensitiveMultiMap();

    requestJson.forEach(
        entry -> {
          if (entry.getKey().equalsIgnoreCase("geoQ")
              || entry.getKey().equalsIgnoreCase("temporalQ")) {
            JsonObject innerObject = (JsonObject) entry.getValue();
            paramsMap.add(entry.getKey().toString(), entry.getValue().toString());
            innerObject.forEach(
                innerentry -> {
                  paramsMap.add(innerentry.getKey().toString(), innerentry.getValue().toString());
                });
          } else if (entry.getKey().equalsIgnoreCase("entities")) {
            paramsMap.add(entry.getKey().toString(), entry.getValue().toString());
            JsonArray array = (JsonArray) entry.getValue();
            JsonObject innerObject = array.getJsonObject(0);
            innerObject.forEach(
                innerentry -> {
                  paramsMap.add(innerentry.getKey().toString(), innerentry.getValue().toString());
                });
          } else {
            paramsMap.add(entry.getKey().toString(), entry.getValue().toString());
          }
        });
    return paramsMap;
  }
}
