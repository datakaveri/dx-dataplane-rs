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
import org.cdpg.dx.database.elastic.service.ElasticsearchService;

/** Helper to stream all data from Elasticsearch using the Scroll API as CSV. */
public class CsvScrollStreamHelper {
  private static final Logger LOGGER = LogManager.getLogger(CsvScrollStreamHelper.class);
  private static final String SCROLL_TIMEOUT = "5m";
  private static final int BATCH_SIZE = 10000;

  public static Future<ReadStream<Buffer>> streamCsvScroll(
      ElasticsearchService elasticsearchService, String index, QueryModel baseQuery) {
    return Future.succeededFuture(
        new ReadStream<Buffer>() {
          private Handler<Buffer> dataHandler;
          private Handler<Void> endHandler;
          private Handler<Throwable> exceptionHandler;
          private boolean writeHeader = true;
          private boolean ended = false;
          private Set<String> headers;
          private boolean streaming = false;
          private String currentScrollId;

          @Override
          public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.dataHandler = handler;
            if (!streaming) {
              streaming = true;
              startIncrementalStreaming();
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

          private void startIncrementalStreaming() {
            LOGGER.trace("Starting incremental CSV stream for index: {} using REST client", index);

            // Use the REST client method that was working
            elasticsearchService
                .scrollSearch(index, baseQuery, SCROLL_TIMEOUT, "_source")
                .onSuccess(
                    scrollResult -> {
                      if (scrollResult == null) {
                        handleError(new RuntimeException("Scroll result is null"));
                        return;
                      }

                      currentScrollId = scrollResult.getScrollId();
                      List<ElasticsearchResponse> results = scrollResult.getResults();

                      if (results == null) {
                        handleError(new RuntimeException("Scroll results are null"));
                        return;
                      }

                      LOGGER.trace("Initial scroll returned {} documents", results.size());
                      processBatch(results);
                    })
                .onFailure(this::handleError);
          }

          /* private void processBatch(List<ElasticsearchResponse> batch) {
              if (batch == null || batch.isEmpty()) {
                  LOGGER.debug("Empty batch received, ending stream");
                  clearScrollAndEnd();
                  return;
              }

              try {
                  // Extract headers from the first batch
                  if (headers == null && !batch.isEmpty()) {
                      headers = new LinkedHashSet<>();
                      batch.forEach(resp -> {
                          if (resp != null && resp.getSource() != null) {
                              headers.addAll(resp.getSource().fieldNames());
                          }
                      });
                      LOGGER.debug("Extracted CSV headers: {}", headers);
                  }

                  // Convert batch to CSV
                  Buffer csvBuffer = CsvStreamUtil.toCsvBuffer(batch, headers, writeHeader);
                  writeHeader = false;

                  // Send to client immediately
                  if (dataHandler != null && csvBuffer.length() > 0) {
                      dataHandler.handle(csvBuffer);
                      LOGGER.debug("Sent batch of {} documents ({} bytes)", batch.size(), csvBuffer.length());
                  }

                  // Continue with next batch if we have a scroll ID and this was a full batch
                  if (currentScrollId != null && batch.size() >= BATCH_SIZE) {
                      LOGGER.debug("Continuing scroll with ID: {}", currentScrollId);
                      continueScrolling();
                  } else {
                      // Last batch, end the stream
                      LOGGER.debug("Last batch received ({} documents), ending stream", batch.size());
                      clearScrollAndEnd();
                  }

              } catch (Exception e) {
                  handleError(new RuntimeException("Error processing batch", e));
              }
          }*/

          private void processBatch(List<ElasticsearchResponse> batch) {
            if (batch == null || batch.isEmpty()) {
              LOGGER.debug("Empty batch received, ending stream");
              clearScrollAndEnd();
              return;
            }

            try {
              // Extract headers from the first batch
              if (headers == null && !batch.isEmpty()) {
                headers = new LinkedHashSet<>();
                batch.forEach(
                    resp -> {
                      if (resp != null && resp.getSource() != null) {
                        headers.addAll(resp.getSource().fieldNames());
                      }
                    });
                LOGGER.debug("Extracted CSV headers: {}", headers);
              }

              // Convert batch to CSV
              Buffer csvBuffer = CsvStreamUtil.toCsvBuffer(batch, headers, writeHeader);
              writeHeader = false;

              // Send to client immediately
              if (dataHandler != null && csvBuffer.length() > 0) {
                dataHandler.handle(csvBuffer);
                LOGGER.trace(
                    "Sent batch of {} documents ({} bytes)", batch.size(), csvBuffer.length());
              }

              // FIX: Continue scrolling if we have a scroll ID and the batch is not empty
              // Remove the batch size comparison - scroll should continue until no more results
              if (currentScrollId != null && !batch.isEmpty()) {
                LOGGER.trace("Continuing scroll with ID: {}", currentScrollId);
                continueScrolling();
              } else {
                // Last batch, end the stream
                LOGGER.debug("Last batch received ({} documents), ending stream", batch.size());
                clearScrollAndEnd();
              }

            } catch (Exception e) {
              handleError(new RuntimeException("Error processing batch", e));
            }
          }

          private void continueScrolling() {
            if (currentScrollId == null) {
              LOGGER.debug("No scroll ID available, ending stream");
              clearScrollAndEnd();
              return;
            }

            // Use the REST client continueScroll method
            elasticsearchService
                .continueScroll(currentScrollId, SCROLL_TIMEOUT)
                .onSuccess(
                    scrollResult -> {
                      if (scrollResult == null) {
                        handleError(new RuntimeException("Continue scroll result is null"));
                        return;
                      }

                      currentScrollId = scrollResult.getScrollId();
                      List<ElasticsearchResponse> results = scrollResult.getResults();

                      if (results == null) {
                        handleError(new RuntimeException("Continue scroll results are null"));
                        return;
                      }

                      LOGGER.trace("Continue scroll returned {} documents", results.size());
                      processBatch(results);
                    })
                .onFailure(this::handleError);
          }

          private void handleError(Throwable err) {
            LOGGER.error("Failed to stream CSV via scroll for index: {}", index, err);
            if (exceptionHandler != null) {
              exceptionHandler.handle(err);
            }
            clearScrollAndEnd();
          }

          private void clearScrollAndEnd() {
            if (currentScrollId != null) {
              elasticsearchService
                  .clearScroll(currentScrollId)
                  .onComplete(
                      clearResult -> {
                        if (clearResult.failed()) {
                          LOGGER.warn(
                              "Failed to clear scroll context: {}",
                              clearResult.cause().getMessage());
                        }
                        endStream();
                      });
            } else {
              endStream();
            }
          }

          private void endStream() {
            if (!ended) {
              ended = true;
              LOGGER.debug("CSV stream ended for index: {}", index);
              if (endHandler != null) {
                endHandler.handle(null);
              }
            }
          }
        });
  }
}
