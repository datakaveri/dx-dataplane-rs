package org.cdpg.dx.rs.admin.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

public interface OnboardingService {
  Future<Void> createDatasetIndex(String datasetIdOrName, JsonObject mappings);
}


