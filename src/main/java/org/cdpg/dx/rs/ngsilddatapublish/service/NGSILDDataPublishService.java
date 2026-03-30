package org.cdpg.dx.rs.ngsilddatapublish.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.buffer.Buffer;
import java.nio.file.Path;

public interface NGSILDDataPublishService {
  Future<String> publishData(JsonArray ngsildData, String id);

  Future<String> publishDataOnSeek(JsonArray pushedData, String id);
  Future<String> publishDataOnSeekIntoElastic(JsonArray pushedData, String id);
  Future<String> uploadBatchToMinio(JsonArray pushedData, String id);
  Future<String> uploadBatchToMinioAndPublishMetadata(JsonArray pushedData, String id);
  Future<String> uploadFileToMinioAndPublishMetadata(Buffer data, String id, String contentType);
  Future<String> uploadFileToMinioAndPublishMetadata(Path filePath, String id, String contentType);
  Future<String> uploadPathToMinioAndPublishMetadata(
      Path path, String id, String contentType, String originalName);
}
