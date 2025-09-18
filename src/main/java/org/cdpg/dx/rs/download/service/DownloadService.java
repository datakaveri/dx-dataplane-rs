package org.cdpg.dx.rs.download.service;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.cdpg.dx.essearch.model.SearchQuery;
import org.cdpg.dx.rs.download.model.GetRequestModel;

public interface DownloadService {
    Future<ReadStream<Buffer>> streamElasticDataCsvBatched(GetRequestModel getRequestModel);
    Future<ReadStream<Buffer>> streamElasticDataCsvBatched(SearchQuery searchQuery, String id);
    Future<ReadStream<Buffer>> streamElasticDataCsvScroll(GetRequestModel getRequestModel);
    Future<ReadStream<Buffer>> streamElasticDataCsvScroll(SearchQuery searchQuery, String id);
}
