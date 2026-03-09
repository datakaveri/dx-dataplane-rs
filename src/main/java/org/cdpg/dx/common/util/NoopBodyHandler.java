package org.cdpg.dx.common.util;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * A BodyHandler implementation that does nothing. Useful when routes are streamed and should avoid
 * buffering the request body.
 */
public class NoopBodyHandler implements BodyHandler {

  @Override
  public BodyHandler setHandleFileUploads(boolean handleFileUploads) {
    return this;
  }

  @Override
  public BodyHandler setUploadsDirectory(String uploadsDirectory) {
    return this;
  }

  @Override
  public BodyHandler setDeleteUploadedFilesOnEnd(boolean deleteUploadedFilesOnEnd) {
    return this;
  }

  @Override
  public BodyHandler setMergeFormAttributes(boolean mergeFormAttributes) {
    return this;
  }

  @Override
  public BodyHandler setPreallocateBodyBuffer(boolean isPreallocateBodyBuffer) {
    return this;
  }

  @Override
  public BodyHandler setBodyLimit(long bodyLimit) {
    return this;
  }

  @Override
  public void handle(RoutingContext ctx) {
    ctx.next();
  }
}
