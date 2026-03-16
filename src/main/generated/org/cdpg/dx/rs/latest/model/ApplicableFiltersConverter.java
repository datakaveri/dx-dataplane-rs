package org.cdpg.dx.rs.latest.model;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.impl.JsonUtil;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Converter and mapper for {@link org.cdpg.dx.rs.latest.model.ApplicableFilters}.
 * NOTE: This class has been automatically generated from the {@link org.cdpg.dx.rs.latest.model.ApplicableFilters} original class using Vert.x codegen.
 */
public class ApplicableFiltersConverter {


  private static final Base64.Decoder BASE64_DECODER = JsonUtil.BASE64_DECODER;
  private static final Base64.Encoder BASE64_ENCODER = JsonUtil.BASE64_ENCODER;

  public static void fromJson(Iterable<java.util.Map.Entry<String, Object>> json, ApplicableFilters obj) {
    for (java.util.Map.Entry<String, Object> member : json) {
      switch (member.getKey()) {
        case "filterValid":
          if (member.getValue() instanceof Boolean) {
            obj.setFilterValid((Boolean)member.getValue());
          }
          break;
        case "groupId":
          if (member.getValue() instanceof String) {
            obj.setGroupId((String)member.getValue());
          }
          break;
        case "itemFilters":
          if (member.getValue() instanceof JsonArray) {
            java.util.ArrayList<java.lang.String> list =  new java.util.ArrayList<>();
            ((Iterable<Object>)member.getValue()).forEach( item -> {
              if (item instanceof String)
                list.add((String)item);
            });
            obj.setItemFilters(list);
          }
          break;
      }
    }
  }

  public static void toJson(ApplicableFilters obj, JsonObject json) {
    toJson(obj, json.getMap());
  }

  public static void toJson(ApplicableFilters obj, java.util.Map<String, Object> json) {
    json.put("filterValid", obj.isFilterValid());
    if (obj.getGroupId() != null) {
      json.put("groupId", obj.getGroupId());
    }
    if (obj.getItemFilters() != null) {
      JsonArray array = new JsonArray();
      obj.getItemFilters().forEach(item -> array.add(item));
      json.put("itemFilters", array);
    }
  }
}
