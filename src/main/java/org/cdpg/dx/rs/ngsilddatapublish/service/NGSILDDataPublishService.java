package org.cdpg.dx.rs.ngsilddatapublish.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;

public interface NGSILDDataPublishService {
  Future<String> publishData(JsonArray ngsildData, String id);
}
