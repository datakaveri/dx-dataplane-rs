package org.cdpg.dx.rs.admin.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
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

            if (!(v instanceof JsonObject)) {
                continue;
            }
            JsonObject cfg = (JsonObject) v;
            JsonArray typeArr = cfg.getJsonArray("type");

            if (typeArr == null || !typeArr.contains("ValueDescriptor")) {
                continue;
            }

            String schema = cfg.getString("dataSchema");
            if (schema == null) {
                properties.put(field, textWithKeyword()); // fallback
                continue;
            }

            switch (schema) {
                case "iudx:Number":
                case "iudx:Point":
                    properties.put(field, new JsonObject().put("type", "float"));
                    break;
                case "iudx:DateTime":
                    properties.put(field, new JsonObject()
                            .put("type", "date")
                            .put("format", "dd/MM/yyyy||MM/dd/yyyy||strict_date_optional_time||epoch_millis"));
                    break;
                default:
                    properties.put(field, textWithKeyword());
            }
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
                                .put("type", "keyword")));
    }

}


