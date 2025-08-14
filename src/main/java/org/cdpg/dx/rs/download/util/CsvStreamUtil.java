package org.cdpg.dx.rs.download.util;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;

public class CsvStreamUtil {
  public static Buffer toCsvBuffer(
      List<ElasticsearchResponse> responses, Set<String> headers, boolean writeHeader) {
    StringBuilder csvBuilder = new StringBuilder();
    if (writeHeader && headers != null && !headers.isEmpty()) {
      csvBuilder.append(String.join(",", headers)).append("\n");
    }
    if (responses != null && !responses.isEmpty()) {
      for (ElasticsearchResponse resp : responses) {
        String row =
            headers.stream()
                .map(h -> escapeCsv(resp.getSource().getValue(h)))
                .collect(Collectors.joining(","));
        csvBuilder.append(row).append("\n");
      }
    }
    return Buffer.buffer(csvBuilder.toString());
  }

  private static String escapeCsv(Object value) {
    if (value == null) return "";
    String str = value.toString();
    if (str.contains(",") || str.contains("\n") || str.contains("\"")) {
      str = '"' + str.replace("\"", "\"\"") + '"';
    }
    return str;
  }

  // Inner class for chunked streaming
  private static class ChunkedBufferReadStream implements ReadStream<Buffer> {
    private final Buffer buffer;
    private final int chunkSize;
    private int position = 0;
    private Handler<Buffer> dataHandler;
    private Handler<Void> endHandler;
    private Handler<Throwable> exceptionHandler;
    private boolean paused = false;
    private boolean ended = false;

    ChunkedBufferReadStream(Buffer buffer, int chunkSize) {
      this.buffer = buffer;
      this.chunkSize = chunkSize;
    }

    @Override
    public ReadStream<Buffer> handler(Handler<Buffer> handler) {
      this.dataHandler = handler;
      if (dataHandler != null && !paused && !ended) {
        streamChunks();
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
      this.paused = true;
      return this;
    }

    @Override
    public ReadStream<Buffer> resume() {
      if (paused) {
        paused = false;
        streamChunks();
      }
      return this;
    }

    @Override
    public ReadStream<Buffer> fetch(long amount) {
      // Not implemented for this simple stream
      return this;
    }

    private void streamChunks() {
      while (!paused && position < buffer.length()) {
        int end = Math.min(position + chunkSize, buffer.length());
        Buffer chunk = buffer.getBuffer(position, end);
        position = end;
        if (dataHandler != null) {
          dataHandler.handle(chunk);
        }
      }
      if (!ended && position >= buffer.length()) {
        ended = true;
        if (endHandler != null) {
          endHandler.handle(null);
        }
      }
    }
  }
}
