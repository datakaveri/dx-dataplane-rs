package org.cdpg.dx.rs.latest.service;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.cdpg.dx.common.request.PaginatedRequest;
import org.cdpg.dx.common.response.ResponseModel;
import org.cdpg.dx.database.elastic.model.QueryDecoderRequestDTO;

public interface LatestService {

  Future<ResponseModel> getLatestData(
      String rsId, int size, int page, String time, String endTime, String timeRel);

  Future<ResponseModel> getLatestData(String rsId, int size, int page);

  Future<ResponseModel> postSearch(QueryDecoderRequestDTO queryDecoder, String id);
  Future<ReadStream<Buffer>> streamDataCsvBatched(String rsId, int size, int page, String time, String endTime, String timeRel);
  Future<ReadStream<Buffer>> streamDataCsvBatched(String rsId, int size, int page);

}
