package org.cdpg.dx.rs.admin.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

public interface OnboardingService {

  /**
   * Creates the Elasticsearch index backing a dataset.
   *
   * @param dynamic how the index treats fields the descriptor does not declare; {@code null} falls
   *     back to the implementation default
   */
  Future<Void> createDatasetIndex(
      String datasetIdOrName, JsonObject dataDescriptor, String dynamic);
}
