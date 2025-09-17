package org.cdpg.dx.rs.admin.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.rs.indexgenerator.IndexNameCreation;

public class OnboardingServiceImpl implements OnboardingService {
  private static final Logger LOGGER = LogManager.getLogger(OnboardingServiceImpl.class);
  private final ElasticsearchService elasticsearchService;

  public OnboardingServiceImpl(ElasticsearchService elasticsearchService) {
    this.elasticsearchService = elasticsearchService;
  }       

  @Override
  public Future<Void> createDatasetIndex(String datasetId, JsonObject dataDescriptor) {
    String finalIndex = IndexNameCreation.createIndex(datasetId);
    LOGGER.debug("Creating index {} via onboarding service", finalIndex);
    JsonObject mapping = buildMappingFromDataDescriptor(dataDescriptor);
    return elasticsearchService.createIndex(finalIndex, mapping);
  }


    public static JsonObject buildMappingFromDataDescriptor(JsonObject dataDescriptor) {
        JsonObject properties = new JsonObject();

        // Map descriptor meta fields if present
        for (String meta : new String[] {"@context", "type", "dataDescriptorLabel", "description"}) {
            if (dataDescriptor.containsKey(meta) && dataDescriptor.getValue(meta) instanceof String) {
                properties.put(meta, textWithKeyword());
            }
        }

        for (String field : dataDescriptor.fieldNames()) {
            Object v = dataDescriptor.getValue(field);

            if (!(v instanceof JsonObject cfg)) {
                continue;
            }
            JsonArray typeArr = cfg.getJsonArray("type");

            if (typeArr == null || !typeArr.contains("ValueDescriptor")) {
                continue;
            }

            String schema = cfg.getString("dataSchema");
            if (schema == null) {
                properties.put(field, textWithKeyword()); // fallback
                continue;
            }

            String normalized = schema.trim();
            if (normalized.toLowerCase().startsWith("iudx:")) {
                normalized = normalized.substring(5);
            }
            else {
                throw new DxBadRequestException(
                        "Invalid dataSchema for field '" + field + "': '" + schema + "'");
            }
            normalized = normalized.toLowerCase();

            JsonObject fieldMapping = switch (normalized) {
                case "boolean" -> new JsonObject().put("type", "boolean");
                case "keyword" -> new JsonObject().put("type", "keyword");
                case "text" -> new JsonObject().put("type", "text");
                case "binary" -> new JsonObject().put("type", "binary");
                case "version" -> new JsonObject().put("type", "version");
                case "datetime", "date", "time" ->
                        new JsonObject()
                                .put("type", "date")
                                .put("format", "dd/MM/yyyy||MM/dd/yyyy||strict_date_optional_time||epoch_millis");
                case "object" -> new JsonObject().put("type", "object");
                case "nested" -> new JsonObject().put("type", "nested");
                case "ip" -> new JsonObject().put("type", "ip");
                case "geopoint" -> new JsonObject().put("type", "geo_point");
                case "geoshape" -> new JsonObject().put("type", "geo_shape");
                case "shape" -> new JsonObject().put("type", "shape");
                case "number" -> pickNumericMapping(cfg);
                case "point" -> new JsonObject().put("type", "float");
                default -> throw new DxBadRequestException(
                        "Invalid dataSchema for field '" + field + "': '" + schema + "'");
            };

            properties.put(field, fieldMapping);
        }

        return new JsonObject()
                .put("mappings", new JsonObject()
                        .put("dynamic", "false")
                        .put("properties", properties));
    }

    private static JsonObject textWithKeyword() {
        return new JsonObject()
                .put("type", "text")
                .put("fields", new JsonObject()
                        .put("keyword", new JsonObject()
                                .put("type", "keyword").put("ignore_above", 256)));
    }

    private static JsonObject pickNumericMapping(JsonObject cfg) {
        String category = cfg.getString("numberCategory", "float");
         {
             return switch (category) {
                 case "byte" -> new JsonObject().put("type", "byte");
                 case "short" -> new JsonObject().put("type", "short");
                 case "integer" -> new JsonObject().put("type", "integer");
                 case "long" -> new JsonObject().put("type", "long");
                 case "float" -> new JsonObject().put("type", "float");
                 case "half_float" -> new JsonObject().put("type", "half_float");
                 case "scaled_float" -> new JsonObject().put("type", "scaled_float")
                         .put("scaling_factor", cfg.getInteger("scaling_factor", 100));
                 case "double" -> new JsonObject().put("type", "double");
                 case "unsigned_long" -> new JsonObject().put("type", "unsigned_long");
                 default -> throw new DxBadRequestException(
                         "Invalid dataSchema for number " + category);
             };

        }
    }

}


