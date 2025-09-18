package org.cdpg.dx.rs.download.util;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.database.elastic.model.QueryModel;
import org.cdpg.dx.database.elastic.model.ScrollResult;
import org.cdpg.dx.database.elastic.service.ElasticsearchScrollService;

/**
 * Helper to stream all data from Elasticsearch using the Scroll API as CSV.
 */
public class CsvScrollStreamHelper {
  private static final Logger LOGGER = LogManager.getLogger(CsvScrollStreamHelper.class);
  private static final String SCROLL_TIMEOUT = "2m"; // Can be made configurable
  private static final int BATCH_SIZE = 1000; // Can be made configurable

  public static Future<ReadStream<Buffer>> streamCsvScroll(
      ElasticsearchScrollService elasticsearchService,
      String index,
      QueryModel baseQuery) {
    return Future.succeededFuture(new ReadStream<Buffer>() {
      private Handler<Buffer> dataHandler;
      private Handler<Void> endHandler;
      private Handler<Throwable> exceptionHandler;
      private boolean writeHeader = true;
      private boolean ended = false;
      private Set<String> headers;
      private String scrollId;
      private boolean started = false;

      @Override
      public ReadStream<Buffer> handler(Handler<Buffer> handler) {
        this.dataHandler = handler;
        if (!started) {
          started = true;
          fetchAndStreamFirstBatch();
        }
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
      public ReadStream<Buffer> pause() { return this; }
      @Override
      public ReadStream<Buffer> resume() { return this; }
      @Override
      public ReadStream<Buffer> fetch(long amount) { return this; }

      private void fetchAndStreamFirstBatch() {
        // Set batch size in query
        baseQuery.setLimit(String.valueOf(BATCH_SIZE));
        elasticsearchService.scrollSearchRest(index, baseQuery, SCROLL_TIMEOUT, "_source")
          .onSuccess(this::handleBatch)
          .onFailure(this::handleError);
      }

      private void fetchAndStreamNextBatch() {
        if (scrollId == null) {
          endStream();
          return;
        }
        elasticsearchService.continueScrollRest(scrollId, SCROLL_TIMEOUT)
          .onSuccess(this::handleBatch)
          .onFailure(this::handleError);
      }

      private void handleBatch(ScrollResult result) {
        List<ElasticsearchResponse> batch = result.getResults();
        scrollId = result.getScrollId();
        if (batch == null || batch.isEmpty()) {
          clearScrollAndEnd();
          return;
        }
        if (headers == null) {
          headers = new LinkedHashSet<>();
          batch.forEach(resp -> headers.addAll(resp.getSource().fieldNames()));
        }
        Buffer csvBuffer = CsvStreamUtil.toCsvBuffer(batch, headers, writeHeader);
        writeHeader = false;
        if (dataHandler != null) dataHandler.handle(csvBuffer);
        // If less than batch size, this is the last batch
        if (batch.size() < BATCH_SIZE) {
          clearScrollAndEnd();
        } else {
          fetchAndStreamNextBatch();
        }
      }

      private void handleError(Throwable err) {
        LOGGER.error("Failed to stream CSV via scroll", err);
        if (exceptionHandler != null) exceptionHandler.handle(err);
        clearScrollAndEnd();
      }

      private void clearScrollAndEnd() {
        if (scrollId != null) {
          elasticsearchService.clearScrollRest(scrollId)
            .onComplete(x -> endStream());
        } else {
          endStream();
        }
      }

      private void endStream() {
        if (!ended) {
          ended = true;
          if (endHandler != null) endHandler.handle(null);
        }
      }
    });
  }
}
