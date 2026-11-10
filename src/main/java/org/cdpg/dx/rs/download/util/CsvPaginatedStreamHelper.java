/*
package org.cdpg.dx.rs.download.util;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;

public class CsvPaginatedStreamHelper {
  public static Future<ReadStream<Buffer>> streamCsvPaginated(
      ElasticsearchService elasticsearchService,
      String index,
      QueryModel baseQuery,
      int size,
      int startPage) {
    return Future.succeededFuture(
        new ReadStream<Buffer>() {
          private static final int MAX_PAGES = 10000; // Failsafe
          private Handler<Buffer> dataHandler;
          private Handler<Void> endHandler;
          private Handler<Throwable> exceptionHandler;
          private boolean writeHeader = true;
          private boolean ended = false;
          private Set<String> headers;
          private List<Object> lastSortValues = null;
          private List<Object> prevSortValues = null;
          private boolean isFirstPage = true;
          private int pageCount = 0;
          private String prevLastDocId = null;

          @Override
          public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.dataHandler = handler;
            fetchAndStream();
            return this;
          }

          @Override
          public ReadStream<Buffer> endHandler(Handler<Void> handler) {
            this.endHandler = handler;
            return this;
          }

          @Override
          public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            this.exceptionHandler = handler;
            return this;
          }

          @Override
          public ReadStream<Buffer> pause() {
            return this;
          }

          @Override
          public ReadStream<Buffer> resume() {
            return this;
          }

          @Override
          public ReadStream<Buffer> fetch(long amount) {
            return this;
          }

          private void fetchAndStream() {
            if (ended) return;
            QueryModel query = baseQuery;
            query.setLimit(String.valueOf(size));
            Future<List<ElasticsearchResponse>> searchFuture;
            if (isFirstPage) {
              searchFuture =
                  ((org.cdpg.dx.database.elastic.service.ElasticsearchServiceImpl)
                          elasticsearchService)
                      .search(index, query, "_source", (List<Object>) null);
              isFirstPage = false;
            } else {
              searchFuture =
                  ((org.cdpg.dx.database.elastic.service.ElasticsearchServiceImpl)
                          elasticsearchService)
                      .search(index, query, "_source", lastSortValues);
            }
            searchFuture.onComplete(
                ar -> {
                  if (ar.failed()) {
                    if (exceptionHandler != null) exceptionHandler.handle(ar.cause());
                    if (endHandler != null) endHandler.handle(null);
                    ended = true;
                    return;
                  }
                  List<ElasticsearchResponse> results = ar.result();
                  if (results == null || results.isEmpty()) {
                    if (endHandler != null) endHandler.handle(null);
                    ended = true;
                    return;
                  }
                  // Log IDs and sort values for this page
                  StringBuilder ids = new StringBuilder();
                  for (ElasticsearchResponse resp : results) {
                    ids.append(resp.getId()).append(",");
                  }
                  if (headers == null) {
                    headers = new LinkedHashSet<>();
                    results.forEach(resp -> headers.addAll(resp.getSource().fieldNames()));
                  }
                  Buffer csvBuffer = CsvStreamUtil.toCsvBuffer(results, headers, writeHeader);
                  writeHeader = false;
                  if (dataHandler != null) dataHandler.handle(csvBuffer);
                  // Prepare search_after for next page
                  ElasticsearchResponse last = results.get(results.size() - 1);
                  prevSortValues = lastSortValues;
                  lastSortValues = last.getSortValues();
                  pageCount++;
                  // Infinite loop protection: if sort values don't change, break
                  if (prevSortValues != null
                      && lastSortValues != null
                      && prevSortValues.equals(lastSortValues)) {
                    System.err.println(
                        "[CsvPaginatedStreamHelper] Detected repeated sort values, breaking to avoid infinite loop.");
                    if (endHandler != null) endHandler.handle(null);
                    ended = true;
                    return;
                  }
                  // Infinite loop protection: if last doc ID is repeated, break
                  String lastDocId = last.getId();
                  if (prevLastDocId != null && prevLastDocId.equals(lastDocId)) {
                    System.err.println(
                        "[CsvPaginatedStreamHelper] Detected repeated last doc ID ("
                            + lastDocId
                            + "), breaking to avoid infinite loop.");
                    if (endHandler != null) endHandler.handle(null);
                    ended = true;
                    return;
                  }
                  prevLastDocId = lastDocId;
                  if (pageCount > MAX_PAGES) {
                    System.err.println(
                        "[CsvPaginatedStreamHelper] Max page limit reached, breaking to avoid runaway loop.");
                    if (endHandler != null) endHandler.handle(null);
                    ended = true;
                    return;
                  }
                  fetchAndStream();
                });
          }
        });
  }
}
*/
