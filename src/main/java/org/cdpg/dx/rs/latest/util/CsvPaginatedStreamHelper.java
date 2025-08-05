package org.cdpg.dx.rs.latest.util;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.Handler;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.database.elastic.model.ElasticsearchResponse;
import org.cdpg.dx.database.elastic.model.QueryModel;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

public class CsvPaginatedStreamHelper {
    public static Future<ReadStream<Buffer>> streamCsvPaginated(
            ElasticsearchService elasticsearchService,
            String index,
            QueryModel baseQuery,
            int size,
            int startPage) {
        return Future.succeededFuture(new ReadStream<Buffer>() {
            private Handler<Buffer> dataHandler;
            private Handler<Void> endHandler;
            private Handler<Throwable> exceptionHandler;
            private int page = startPage;
            private boolean writeHeader = true;
            private boolean ended = false;
            private Set<String> headers;

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
            public ReadStream<Buffer> pause() { return this; }
            @Override
            public ReadStream<Buffer> resume() { return this; }
            @Override
            public ReadStream<Buffer> fetch(long amount) { return this; }

            private void fetchAndStream() {
                if (ended) return;
                QueryModel query = baseQuery;
                query.setLimit(String.valueOf(size));
                query.setOffset(String.valueOf((page - 1) * size));
                elasticsearchService.search(index, query, "_source").onComplete(ar -> {
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
                    if (headers == null) {
                        headers = new LinkedHashSet<>();
                        results.forEach(resp -> headers.addAll(resp.getSource().fieldNames()));
                    }
                    Buffer csvBuffer = CsvStreamUtil.toCsvBuffer(results, headers, writeHeader);
                    writeHeader = false;
                    if (dataHandler != null) dataHandler.handle(csvBuffer);
                    page++;
                    fetchAndStream();
                });
            }
        });
    }
}
