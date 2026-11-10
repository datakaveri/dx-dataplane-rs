package org.cdpg.dx.validations.idhandler;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.parsetools.JsonParser;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.util.RoutingContextHelper;

/**
 * Extracts the entity id from a streamed ingestion payload.
 *
 * <p>The publish API server streams request bodies — no {@code BodyHandler} is mounted, so {@link
 * RoutingContext#body()} is empty. This handler consumes only as much of the request as is needed
 * to parse the first JSON object, stores the bytes it consumed under {@link #CONSUMED_BODY_KEY} so
 * the controller can replay them into its own parser, and leaves the request paused for the rest of
 * the handler chain.
 *
 * <p>Both payload shapes are accepted:
 *
 * <pre>
 *   {"entities": "&lt;id&gt;", ...}                              single object
 *   [{"entities": "&lt;id&gt;", ...}, {"entities": "&lt;id&gt;", ...}]   array of objects
 * </pre>
 */
public class IngestionEntityIdStreamHandler implements Handler<RoutingContext> {
  /** Context key holding the request bytes this handler consumed while looking for the id. */
  public static final String CONSUMED_BODY_KEY = "ingestionConsumedBody";

  private static final String JSON_ENTITIES = "entities";
  private static final Logger LOGGER = LogManager.getLogger(IngestionEntityIdStreamHandler.class);

  /**
   * Reads the entity id from an ingestion object. Accepts {@code "entities": "<id>"} and {@code
   * "entities": ["<id>"]}; returns null when the field is absent, empty or of any other type.
   */
  public static String extractEntityId(JsonObject entity) {
    if (entity == null) {
      return null;
    }
    Object value = entity.getValue(JSON_ENTITIES);
    if (value instanceof JsonArray array) {
      value = array.isEmpty() ? null : array.getValue(0);
    }
    if (value instanceof String id && !id.isBlank()) {
      return id;
    }
    return null;
  }

  @Override
  public void handle(RoutingContext context) {
    HttpServerRequest request = context.request();
    LOGGER.debug("Info : path {}", RoutingContextHelper.getRequestPath(context));

    if (request.isEnded()) {
      context.fail(new DxBadRequestException("Empty request body"));
      return;
    }

    Buffer consumed = Buffer.buffer();
    JsonParser parser = JsonParser.newParser();
    parser.objectValueMode();
    boolean[] settled = {false};

    parser.handler(
        event -> {
          if (settled[0]) {
            return;
          }
          JsonObject entity = event.objectValue();
          if (entity == null) {
            // Skip non-object tokens (start/end of the enclosing array).
            return;
          }
          String id = extractEntityId(entity);
          if (id == null) {
            fail(
                settled,
                context,
                new DxBadRequestException(
                    "Mandatory field 'entities' is missing or empty in the request body"));
            return;
          }
          settled[0] = true;
          request.pause();
          RoutingContextHelper.setId(context, id);
          context.put(CONSUMED_BODY_KEY, consumed);
          LOGGER.info("id :{}", id);
          context.next();
        });

    parser.exceptionHandler(
        err ->
            fail(
                settled, context, new DxBadRequestException("Invalid JSON: " + err.getMessage())));

    request.handler(
        buffer -> {
          if (settled[0]) {
            return;
          }
          consumed.appendBuffer(buffer);
          parser.handle(buffer);
        });

    request.endHandler(
        ignored -> {
          if (settled[0]) {
            return;
          }
          try {
            parser.end();
          } catch (Exception e) {
            LOGGER.error("Failed to parse ingestion payload: {}", e.getMessage());
          }
          fail(settled, context, new DxBadRequestException("No JSON object found in request body"));
        });

    request.exceptionHandler(err -> fail(settled, context, err));

    request.resume();
  }

  private void fail(boolean[] settled, RoutingContext context, Throwable cause) {
    if (settled[0]) {
      return;
    }
    settled[0] = true;
    LOGGER.error("Error : {}", cause.getMessage());
    context.fail(cause);
  }
}
