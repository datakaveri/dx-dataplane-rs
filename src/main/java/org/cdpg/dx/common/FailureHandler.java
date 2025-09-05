package org.cdpg.dx.common;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.validation.BodyProcessorException;
import io.vertx.ext.web.validation.ParameterProcessorException;
import io.vertx.ext.web.validation.RequestPredicateException;
import io.vertx.json.schema.ValidationException;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.response.DxErrorResponse;
import org.cdpg.dx.common.util.ExceptionHttpStatusMapper;
import org.cdpg.dx.common.util.ThrowableUtils;

public class FailureHandler implements Handler<RoutingContext> {

  private static final Logger LOGGER = LogManager.getLogger(FailureHandler.class);
  private final URNGenerator urnGenerator;

  public FailureHandler(URNGenerator urnGenerator) {
    this.urnGenerator = urnGenerator;
  }

  @Override
  public void handle(RoutingContext context) {
    Throwable failure = context.failure();
    int statusCodeFromContext = context.statusCode();

    // Case 1: OpenAPI validation / schema errors
    if (failure instanceof ValidationException
        || failure instanceof BodyProcessorException
        || failure instanceof RequestPredicateException
        || failure instanceof ParameterProcessorException) {

      LOGGER.warn("Validation error: {}", failure.getMessage());

      context
          .response()
          .putHeader(CONTENT_TYPE, APPLICATION_JSON)
          .putHeader(HEADER_ALLOW_ORIGIN, "*")
          .putHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
          .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
          .setStatusCode(HttpStatus.SC_BAD_REQUEST)
          .end(
              ResponseUtil.generateResponse(
                      HttpStatusCode.BAD_REQUEST,
                      urnGenerator.generateUrn(HttpStatusCode.BAD_REQUEST.getPath()),
                      "Missing or malformed request")
                  .toString());
      return;
    }

    // Case 2: ctx.fail(statusCode) without Throwable
    if (failure == null && statusCodeFromContext != -1) {
      LOGGER.warn("FailureHandler triggered with only statusCode: {}", statusCodeFromContext);

      DxErrorResponse errorResponse =
          new DxErrorResponse(
              urnGenerator.generateUrn("bad_request"), "Request failed", "Bad request");

      context
          .response()
          .putHeader(CONTENT_TYPE, APPLICATION_JSON)
          .putHeader(HEADER_ALLOW_ORIGIN, "*")
          .putHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
          .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
          .setStatusCode(statusCodeFromContext)
          .end(errorResponse.toJson().encode());
      return;
    }

    // Case 3: Unexpected exceptions
    HttpStatusCode statusCode = ExceptionHttpStatusMapper.map(failure);
    LOGGER.error("Unhandled error: {}", failure != null ? failure.getMessage() : "null", failure);

    String safeDetail =
        failure != null && ThrowableUtils.isSafeToExpose(failure)
            ? failure.getMessage()
            : "An unexpected error occurred";

    String urn = urnGenerator.generateUrn(statusCode.getPath());

    DxErrorResponse errorResponse =
        new DxErrorResponse(urn, statusCode.getDescription(), safeDetail);

    if (!context.response().ended()) {
      int status = statusCode.getValue();
      if (status < 400 || status > 599) {
        status = 500;
      }

      context
          .response()
          .putHeader(CONTENT_TYPE, APPLICATION_JSON)
          .putHeader(HEADER_ALLOW_ORIGIN, "*")
          .putHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
          .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
          .setStatusCode(status)
          .end(errorResponse.toJson().encode());
    }
  }
}
