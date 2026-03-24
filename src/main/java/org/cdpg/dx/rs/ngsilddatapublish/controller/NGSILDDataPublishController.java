package org.cdpg.dx.rs.ngsilddatapublish.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.HEADER_ALLOW_ORIGIN;
import static org.cdpg.dx.databroker.util.Constants.*;
import static org.cdpg.dx.rs.audit.util.Constants.*;
import static org.cdpg.dx.rs.ngsilddatapublish.util.Constants.*;

import io.vertx.core.Future;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;

import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.apiserver.FileUploadManager;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auditing.model.AuditLog;
import org.cdpg.dx.auth.authorization.handler.AuthorizationHandler;
import org.cdpg.dx.auth.authorization.model.DxRole;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.common.exception.DxBadRequestException;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.common.validations.idhandler.GetIdForIngestionEntityHandler;
import org.cdpg.dx.common.validations.idhandler.GetIdFromPathHandler;
import org.cdpg.dx.rs.audit.util.DataplaneAuditHelper;
import org.cdpg.dx.rs.ngsilddatapublish.service.NGSILDDataPublishService;
import org.cdpg.dx.validations.idvalidation.IdValidation;
import org.cdpg.dx.validations.itemandfiltercheck.ItemAccessDataPublishHandler;
import org.cdpg.dx.validations.provider.ProviderDelegateValidationHandler;


public class NGSILDDataPublishController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(NGSILDDataPublishController.class);
  private final IdValidation idValidation;
  private final int chunkMaxItems;
  private final int chunkMaxBytes;
  private final GetIdForIngestionEntityHandler getIdForIngestionEntityHandler;
  private final GetIdFromPathHandler getIdFromPathHandler;
  private final ItemAccessDataPublishHandler itemAccessDataPublishHandler;
  private final ProviderDelegateValidationHandler providerDelegateValidationHandler;
  private NGSILDDataPublishService ngsildDataPublishService;
  private URNGenerator urnGenerator;
  private AuditingHandler auditingHandler;

  public NGSILDDataPublishController(
      NGSILDDataPublishService ngsildDataPublishService,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      AuditingHandler auditingHandler,
      int chunkMaxItems,
      int chunkMaxBytes) {
    this.chunkMaxItems = chunkMaxItems;
    this.chunkMaxBytes = chunkMaxBytes * 1024 * 1024; // convert MB to Bytes
    this.auditingHandler = auditingHandler;
    this.itemAccessDataPublishHandler = new ItemAccessDataPublishHandler(controlPlaneDomain);
    this.urnGenerator = urnGenerator;
    this.ngsildDataPublishService = ngsildDataPublishService;
    this.idValidation = new IdValidation();
    this.getIdForIngestionEntityHandler = new GetIdForIngestionEntityHandler();
    this.getIdFromPathHandler = new GetIdFromPathHandler();
    this.providerDelegateValidationHandler = new ProviderDelegateValidationHandler();
  }

  @Override
  public void register(RouterBuilder builder) {
    builder
        .operation(POST_NGSILD_ENTITY_PUBLISH)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdForIngestionEntityHandler)
        .handler(AuthorizationHandler.forRoles(DxRole.PROVIDER, DxRole.DELEGATE))
        .handler(itemAccessDataPublishHandler)
        .handler(providerDelegateValidationHandler)
        .handler(idValidation)
        .handler(context -> handleDataPublish(context));
    builder
        .operation(POST_NGSILD_ENTITY_PUBLISH_ONSEEK)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(AuthorizationHandler.forRoles(DxRole.PROVIDER, DxRole.DELEGATE))
        .handler(itemAccessDataPublishHandler)
        .handler(providerDelegateValidationHandler)
        .handler(idValidation)
        .handler(context -> handleDataPublishAlias(context));

    builder
        .operation(POST_NGSILD_ENTITY_PUBLISH_ALIAS)
        .handler(auditingHandler::handleApiAudit)
        .handler(getIdFromPathHandler)
        .handler(AuthorizationHandler.forRoles(DxRole.PROVIDER, DxRole.DELEGATE))
        .handler(itemAccessDataPublishHandler)
        .handler(providerDelegateValidationHandler)
        .handler(idValidation)
        .handler(context -> handleDataPublishAlias(context));
  }

  public void handleDataPublish(RoutingContext context) {
    LOGGER.info("Handling NGSILD Data Publish Request");
    String id = RoutingContextHelper.getId(context);
    streamAndPublish(context, id, false);
  }

  public void handleDataPublishAlias(RoutingContext context) {
    LOGGER.info("Handling NGSI-LD Data Publish Request Alias");
    String id = RoutingContextHelper.getId(context);
    if (id == null) {
      id = context.pathParam("id");
    }
    streamAndPublish(context, id, true);
  }

  private void streamAndPublish(RoutingContext context, String id, boolean onSeek) {
    // Check if the request contains a file ID instead of raw data
    byte[] body = context.getBody().getBytes();
    if (body != null && body.length > 0) {
      try {
        // Try to parse as JSON to check for fileId
        JsonObject bodyJson = new JsonObject(new String(body));
        String fileId = bodyJson.getString("fileId");

        if (fileId != null && !fileId.isEmpty()) {
          // This is a file ID reference - retrieve the file and stream it
          LOGGER.info("Detected file ID in request: {}", fileId);
          streamFromUploadedFile(context, id, fileId, onSeek);
          return;
        }
      } catch (Exception e) {
        // Body is not a JSON object (likely a JSON array) – stream it directly from the buffered body
        LOGGER.debug("Body is not JSON object or doesn't contain fileId, will stream buffered body directly");
      }

      // At this point we have a buffered body but no fileId; stream from the buffered payload
      streamFromFileData(context, body, id, "inline-body", onSeek, false);
      return;
    }

    // Fallback: stream directly from the request (when no body buffer is available)
    streamWithVertxParser(context, id, onSeek);
  }

  private void streamFromUploadedFile(RoutingContext context, String id, String fileId, boolean onSeek) {
    // Check if file is on disk (large file) - stream from disk without loading into memory
    java.nio.file.Path diskPath = FileUploadManager.getFilePath(fileId);
    if (diskPath != null) {
      // File is on disk - stream from disk
      LOGGER.info("File is on disk, streaming from: {} for item ID: {}", diskPath, id);
      streamFromDiskPath(context, diskPath, id, fileId, onSeek, true);
      return;
    }
    
    // File might be in memory (small file)
    byte[] fileData = FileUploadManager.getFileData(fileId);
    if (fileData != null && fileData.length > 0) {
      LOGGER.info("File is in memory, size: {} bytes, streaming for item ID: {}", fileData.length, id);
      streamFromMemoryData(context, fileData, id, fileId, onSeek, true);
      return;
    }
    
    // File not found
    LOGGER.error("File not found for ID: {}", fileId);
    context.fail(new DxBadRequestException("File not found: " + fileId));
  }

  private void streamFromDiskPath(RoutingContext context, java.nio.file.Path diskPath, String id, String fileId, boolean onSeek, boolean deleteAfterPublish) {
    context.vertx().executeBlocking(
        future -> {
          try {
            long fileSize = java.nio.file.Files.size(diskPath);
            
            // Use a custom FileReadStream to stream from disk without loading all into memory
            FileReadStream fileStream = new FileReadStream(diskPath, context.vertx());
            
            io.vertx.core.parsetools.JsonParser parser =
                io.vertx.core.parsetools.JsonParser.newParser(fileStream);
            parser.objectValueMode();
            parser.pause();

            JsonArray batch = new JsonArray();
            int[] batchBytes = {0};
            long[] totalCount = {0};
            long[] totalPushed = {0};
            boolean[] failed = {false};
            String[] idRef = {id};
            long startTime = System.currentTimeMillis();

            LOGGER.info("================================================================================");
            LOGGER.info("Starting data import from disk file: {} for item ID: {}", fileId, id);
            LOGGER.info("File size: {} bytes ({} MB)", fileSize, fileSize / (1024 * 1024));
            LOGGER.info("================================================================================");

            parser.handler(event -> {
              if (failed[0]) return;
              parser.pause();
              
              JsonObject obj = event.objectValue();
              if (obj == null) {
                parser.resume();
                return;
              }
              
              if (idRef[0] == null) {
                idRef[0] = obj.getString("entities");
              }
              if (idRef[0] == null || idRef[0].isBlank()) {
                failed[0] = true;
                future.fail(new DxBadRequestException("Missing id in payload"));
                return;
              }
              
              batch.add(obj);
              batchBytes[0] += obj.encode().length();
              totalCount[0]++;

              if (batch.size() >= chunkMaxItems || batchBytes[0] >= chunkMaxBytes) {
                long batchSize = batch.size();
                LOGGER.debug("Flushing batch: {} records ({} bytes)", batchSize, batchBytes[0]);
                flushBatch(context, batch.copy(), idRef[0], batchBytes, batch, failed, onSeek)
                    .onSuccess(v -> {
                      totalPushed[0] += batchSize;
                      LOGGER.debug("Batch pushed. Total: {}/{}", totalPushed[0], totalCount[0]);
                      parser.resume();
                    })
                    .onFailure(err -> {
                      failed[0] = true;
                      LOGGER.error("Failed to push batch. Total pushed: {}", totalPushed[0]);
                      future.fail(err);
                    });
              } else {
                parser.resume();
              }
            });

            parser.exceptionHandler(err -> {
              failed[0] = true;
              LOGGER.error("JSON parsing error after {} records", totalCount[0], err);
              future.fail(new DxBadRequestException("Invalid JSON: " + err.getMessage()));
            });

            parser.endHandler(v -> {
              if (failed[0]) return;
              
              if (batch.isEmpty()) {
                logImportComplete(idRef[0], fileId, totalCount[0], totalPushed[0], startTime);
                future.complete();
              } else {
                long batchSize = batch.size();
                flushBatch(context, batch.copy(), idRef[0], batchBytes, batch, failed, onSeek)
                    .onSuccess(ignored -> {
                      totalPushed[0] += batchSize;
                      logImportComplete(idRef[0], fileId, totalCount[0], totalPushed[0], startTime);
                      future.complete();
                    })
                    .onFailure(err -> {
                      logImportFailed(idRef[0], fileId, totalCount[0], totalPushed[0], startTime, err);
                      future.fail(err);
                    });
              }
            });

            parser.resume();
          } catch (Exception e) {
            LOGGER.error("Error streaming from disk file", e);
            future.fail(e);
          }
        },
        result -> {
          if (result.succeeded()) {
            if (deleteAfterPublish) {
              FileUploadManager.deleteFile(fileId);
            }
            respondSuccess(context, context.response(), id);
          } else {
            context.fail(result.cause());
          }
        }
    );
  }

  private void streamFromMemoryData(RoutingContext context, byte[] fileData, String id, String fileId, boolean onSeek, boolean deleteAfterPublish) {
    // ...existing streamFromFileData code...
    streamFromFileData(context, fileData, id, fileId, onSeek, deleteAfterPublish);
  }

  private void logImportComplete(String itemId, String fileId, long totalRead, long totalPushed, long startTime) {
    long totalTime = System.currentTimeMillis() - startTime;
    LOGGER.info("================================================================================");
    LOGGER.info("DATA IMPORT COMPLETED FOR ITEM ID: {}", itemId);
    LOGGER.info("File ID: {}", fileId);
    LOGGER.info("Total records read: {}", totalRead);
    LOGGER.info("Total records pushed: {}", totalPushed);
    LOGGER.info("Processing time: {} ms ({} seconds)", totalTime, totalTime / 1000);
    if (totalRead > 0) {
      LOGGER.info("Performance: {:.2f} records/sec", (totalRead * 1000.0 / totalTime));
    }
    LOGGER.info("================================================================================");
  }

  private void logImportFailed(String itemId, String fileId, long totalRead, long totalPushed, long startTime, Throwable err) {
    long totalTime = System.currentTimeMillis() - startTime;
    LOGGER.error("================================================================================");
    LOGGER.error("DATA IMPORT FAILED FOR ITEM ID: {}", itemId);
    LOGGER.error("File ID: {}", fileId);
    LOGGER.error("Records read: {}", totalRead);
    LOGGER.error("Records pushed: {}", totalPushed);
    LOGGER.error("Failed at: {} ms ({} seconds)", totalTime, totalTime / 1000);
    LOGGER.error("Error: {}", err.getMessage());
    LOGGER.error("================================================================================");
  }

  /**
   * FileReadStream - Streams file from disk in chunks
   */
  private static class FileReadStream implements io.vertx.core.streams.ReadStream<io.vertx.core.buffer.Buffer> {
    private final java.nio.file.Path filePath;
    private final io.vertx.core.Vertx vertx;
    private io.vertx.core.Handler<io.vertx.core.buffer.Buffer> dataHandler;
    private io.vertx.core.Handler<java.lang.Throwable> exceptionHandler;
    private io.vertx.core.Handler<java.lang.Void> endHandler;
    private java.io.FileInputStream fileInputStream;
    private boolean started = false;
    private static final int CHUNK_SIZE = 8192; // 8KB chunks

    FileReadStream(java.nio.file.Path filePath, io.vertx.core.Vertx vertx) {
      this.filePath = filePath;
      this.vertx = vertx;
    }

    @Override
    public FileReadStream handler(io.vertx.core.Handler<io.vertx.core.buffer.Buffer> handler) {
      this.dataHandler = handler;
      if (handler != null && !started) {
        started = true;
        // Start reading file in chunks
        vertx.executeBlocking(future -> {
          try {
            fileInputStream = new java.io.FileInputStream(filePath.toFile());
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
              byte[] chunk = new byte[bytesRead];
              System.arraycopy(buffer, 0, chunk, 0, bytesRead);
              io.vertx.core.buffer.Buffer vertxBuffer = io.vertx.core.buffer.Buffer.buffer(chunk);
              
              // Call handler on context
              vertx.runOnContext(v -> {
                if (dataHandler != null) {
                  dataHandler.handle(vertxBuffer);
                }
              });
            }
            
            fileInputStream.close();
            future.complete();
            
            // Call endHandler after all data read
            if (endHandler != null) {
              vertx.runOnContext(v -> endHandler.handle(null));
            }
          } catch (Exception e) {
            try {
              if (fileInputStream != null) fileInputStream.close();
            } catch (IOException ignored) {}
            if (exceptionHandler != null) {
              vertx.runOnContext(v -> exceptionHandler.handle(e));
            }
          }
        }, ar -> {
          if (ar.failed() && exceptionHandler != null) {
            exceptionHandler.handle(ar.cause());
          }
        });
      }
      return this;
    }

    @Override
    public FileReadStream pause() {
      return this;
    }

    @Override
    public FileReadStream resume() {
      return this;
    }

    @Override
    public FileReadStream endHandler(io.vertx.core.Handler<java.lang.Void> handler) {
      this.endHandler = handler;
      return this;
    }

    @Override
    public FileReadStream exceptionHandler(io.vertx.core.Handler<java.lang.Throwable> handler) {
      this.exceptionHandler = handler;
      return this;
    }

    @Override
    public FileReadStream fetch(long amount) {
      return this;
    }
  }

  private void streamFromFileData(RoutingContext context, byte[] fileData, String id, String fileId, boolean onSeek, boolean deleteAfterPublish) {
    // Use Vertx to handle the stream asynchronously
    context.vertx().executeBlocking(
        future -> {
          try {
            // Convert byte[] to a stream using a custom ReadStream implementation
            io.vertx.core.buffer.Buffer buffer = io.vertx.core.buffer.Buffer.buffer(fileData);
            BufferReadStream bufferStream = new BufferReadStream(buffer, context.vertx());
            
            io.vertx.core.parsetools.JsonParser parser =
                io.vertx.core.parsetools.JsonParser.newParser(bufferStream);
            parser.objectValueMode();
            parser.pause();

            JsonArray batch = new JsonArray();
            int[] batchBytes = {0};
            long[] totalCount = {0};
            long[] totalPushed = {0};
            boolean[] failed = {false};
            String[] idRef = {id};
            long startTime = System.currentTimeMillis();

            LOGGER.info("================================================================================");
            LOGGER.info("Starting data import from file: {} for item ID: {}", fileId, id);
            LOGGER.info("File size: {} bytes ({} MB)", fileData.length, fileData.length / (1024 * 1024));
            LOGGER.info("================================================================================");

            parser.handler(
                event -> {
                  if (failed[0]) {
                    return;
                  }
                  parser.pause();
                  JsonObject obj = event.objectValue();
                  if (obj == null) {
                    parser.resume();
                    return;
                  }
                  if (idRef[0] == null) {
                    idRef[0] = obj.getString("entities");
                  }
                  if (idRef[0] == null || idRef[0].isBlank()) {
                    failed[0] = true;
                    future.fail(new DxBadRequestException("Missing id in payload"));
                    return;
                  }
                  batch.add(obj);
                  batchBytes[0] += obj.encode().length();
                  totalCount[0]++;

                  if (batch.size() >= chunkMaxItems || batchBytes[0] >= chunkMaxBytes) {
                    long batchSize = batch.size();
                    LOGGER.debug("Flushing batch: {} records ({} bytes)", batchSize, batchBytes[0]);
                    flushBatch(context, batch.copy(), idRef[0], batchBytes, batch, failed, onSeek)
                        .onSuccess(v -> {
                          totalPushed[0] += batchSize;
                          LOGGER.debug("Batch pushed successfully. Total pushed so far: {}/{}", totalPushed[0], totalCount[0]);
                          parser.resume();
                        })
                        .onFailure(err -> {
                          failed[0] = true;
                          LOGGER.error("Failed to push batch. Total pushed before failure: {}", totalPushed[0]);
                          future.fail(err);
                        });
                  } else {
                    parser.resume();
                  }
                });

            parser.exceptionHandler(
                err -> {
                  failed[0] = true;
                  LOGGER.error("JSON parsing error after reading {} records", totalCount[0], err);
                  future.fail(new DxBadRequestException("Invalid JSON: " + err.getMessage()));
                });

            parser.endHandler(
                v -> {
                  if (failed[0]) {
                    return;
                  }
                  if (batch.isEmpty()) {
                    long totalTime = System.currentTimeMillis() - startTime;
                    LOGGER.info("================================================================================");
                    LOGGER.info("DATA IMPORT COMPLETED FOR ITEM ID: {}", idRef[0]);
                    LOGGER.info("File ID: {}", fileId);
                    LOGGER.info("Total records read from file: {}", totalCount[0]);
                    LOGGER.info("Total records pushed to Elasticsearch: {}", totalPushed[0]);
                    LOGGER.info("Processing time: {} ms ({} seconds)", totalTime, totalTime / 1000);
                    LOGGER.info("Performance: {:.2f} records/sec", (totalCount[0] * 1000.0 / totalTime));
                    LOGGER.info("================================================================================");
                    future.complete();
                  } else {
                    long batchSize = batch.size();
                    LOGGER.debug("Final batch flush: {} records ({} bytes)", batchSize, batchBytes[0]);
                    flushBatch(context, batch.copy(), idRef[0], batchBytes, batch, failed, onSeek)
                        .onSuccess(
                            ignored -> {
                              totalPushed[0] += batchSize;
                              long totalTime = System.currentTimeMillis() - startTime;
                              LOGGER.info("================================================================================");
                              LOGGER.info("DATA IMPORT COMPLETED FOR ITEM ID: {}", idRef[0]);
                              LOGGER.info("File ID: {}", fileId);
                              LOGGER.info("Total records read from file: {}", totalCount[0]);
                              LOGGER.info("Total records pushed to Elasticsearch: {}", totalPushed[0]);
                              LOGGER.info("Processing time: {} ms ({} seconds)", totalTime, totalTime / 1000);
                              LOGGER.info("Performance: {:.2f} records/sec", (totalCount[0] * 1000.0 / totalTime));
                              LOGGER.info("================================================================================");
                              future.complete();
                            })
                        .onFailure(err -> {
                          long totalTime = System.currentTimeMillis() - startTime;
                          LOGGER.error("================================================================================");
                          LOGGER.error("DATA IMPORT FAILED FOR ITEM ID: {}", idRef[0]);
                          LOGGER.error("File ID: {}", fileId);
                          LOGGER.error("Records read before failure: {}", totalCount[0]);
                          LOGGER.error("Records pushed before failure: {}", totalPushed[0]);
                          LOGGER.error("Failed at: {} ms ({} seconds)", totalTime, totalTime / 1000);
                          LOGGER.error("Error: {}", err.getMessage());
                          LOGGER.error("================================================================================");
                          future.fail(err);
                        });
                  }
                });

            parser.resume();
          } catch (Exception e) {
            LOGGER.error("Error processing file stream", e);
            future.fail(e);
          }
        },
        result -> {
          if (result.succeeded()) {
            if (deleteAfterPublish) {
              FileUploadManager.deleteFile(fileId);
            }
            respondSuccess(context, context.response(), id);
          } else {
            context.fail(result.cause());
          }
        }
    );
  }

  /**
   * Simple ReadStream implementation for Buffer data
   */
  private static class BufferReadStream implements io.vertx.core.streams.ReadStream<io.vertx.core.buffer.Buffer> {
    private final io.vertx.core.buffer.Buffer buffer;
    private final io.vertx.core.Vertx vertx;
    private io.vertx.core.Handler<io.vertx.core.buffer.Buffer> dataHandler;
    private io.vertx.core.Handler<java.lang.Throwable> exceptionHandler;
    private io.vertx.core.Handler<java.lang.Void> endHandler;
    private boolean ended = false;

    BufferReadStream(io.vertx.core.buffer.Buffer buffer, io.vertx.core.Vertx vertx) {
      this.buffer = buffer;
      this.vertx = vertx;
    }

    @Override
    public BufferReadStream handler(io.vertx.core.Handler<io.vertx.core.buffer.Buffer> handler) {
      this.dataHandler = handler;
      if (handler != null && !ended) {
        // Send the entire buffer in one chunk
        vertx.runOnContext(v -> {
          if (!ended) {
            handler.handle(buffer);
            ended = true;
            if (endHandler != null) {
              endHandler.handle(null);
            }
          }
        });
      }
      return this;
    }

    @Override
    public BufferReadStream pause() {
      return this;
    }

    @Override
    public BufferReadStream resume() {
      return this;
    }

    @Override
    public BufferReadStream endHandler(io.vertx.core.Handler<java.lang.Void> handler) {
      this.endHandler = handler;
      return this;
    }

    @Override
    public BufferReadStream exceptionHandler(io.vertx.core.Handler<java.lang.Throwable> handler) {
      this.exceptionHandler = handler;
      return this;
    }

    @Override
    public BufferReadStream fetch(long amount) {
      return this;
    }
  }

  private void streamWithVertxParser(RoutingContext context, String id, boolean onSeek) {
    // Pause the request to prevent multiple handler access
    context.request().pause();
    io.vertx.core.parsetools.JsonParser parser =
        io.vertx.core.parsetools.JsonParser.newParser(context.request());
    parser.objectValueMode();
    parser.pause();

    JsonArray batch = new JsonArray();
    int[] batchBytes = {0};
    long[] totalCount = {0};
    long[] totalPushed = {0};
    boolean[] failed = {false};
    String[] idRef = {id};
    long startTime = System.currentTimeMillis();

    LOGGER.info("================================================================================");
    LOGGER.info("Starting direct data streaming for item ID: {}", id);
    LOGGER.info("================================================================================");

    parser.handler(
        event -> {
          if (failed[0]) {
            return;
          }
          parser.pause();
          JsonObject obj = event.objectValue();
          if (obj == null) {
            // Skip non-object tokens (e.g., start/end array) in stream.
            parser.resume();
            return;
          }
          if (idRef[0] == null) {
            idRef[0] = obj.getString("entities");
          }
          if (idRef[0] == null || idRef[0].isBlank()) {
            failed[0] = true;
            context.fail(new DxBadRequestException("Missing id in payload"));
            return;
          }
          batch.add(obj);
          batchBytes[0] += obj.encode().length();
          totalCount[0]++;

          if (batch.size() >= chunkMaxItems || batchBytes[0] >= chunkMaxBytes) {
            long batchSize = batch.size();
            LOGGER.debug("Flushing batch: {} records ({} bytes)", batchSize, batchBytes[0]);
            flushBatch(context, batch.copy(), idRef[0], batchBytes, batch, failed, onSeek)
                .onSuccess(v -> {
                  totalPushed[0] += batchSize;
                  LOGGER.debug("Batch pushed successfully. Total pushed so far: {}/{}", totalPushed[0], totalCount[0]);
                  parser.resume();
                })
                .onFailure(err -> {
                  failed[0] = true;
                  LOGGER.error("Failed to push batch. Total pushed before failure: {}", totalPushed[0]);
                });
          } else {
            parser.resume();
          }
        });

    parser.exceptionHandler(
        err -> {
          failed[0] = true;
          LOGGER.error("JSON parsing error after reading {} records", totalCount[0], err);
          context.fail(new DxBadRequestException("Invalid JSON: " + err.getMessage()));
        });

    parser.endHandler(
        v -> {
          if (failed[0]) {
            return;
          }
          if (batch.isEmpty()) {
            long totalTime = System.currentTimeMillis() - startTime;
            LOGGER.info("================================================================================");
            LOGGER.info("DATA IMPORT COMPLETED FOR ITEM ID: {}", idRef[0]);
            LOGGER.info("Total records read from stream: {}", totalCount[0]);
            LOGGER.info("Total records pushed to Elasticsearch: {}", totalPushed[0]);
            LOGGER.info("Processing time: {} ms ({} seconds)", totalTime, totalTime / 1000);
            if (totalCount[0] > 0) {
              LOGGER.info("Performance: {:.2f} records/sec", (totalCount[0] * 1000.0 / totalTime));
            }
            LOGGER.info("================================================================================");
            respondSuccess(context, context.response(), idRef[0]);
          } else {
            long batchSize = batch.size();
            LOGGER.debug("Final batch flush: {} records ({} bytes)", batchSize, batchBytes[0]);
            flushBatch(context, batch.copy(), idRef[0], batchBytes, batch, failed, onSeek)
                .onSuccess(
                    ignored -> {
                      totalPushed[0] += batchSize;
                      long totalTime = System.currentTimeMillis() - startTime;
                      LOGGER.info("================================================================================");
                      LOGGER.info("DATA IMPORT COMPLETED FOR ITEM ID: {}", idRef[0]);
                      LOGGER.info("Total records read from stream: {}", totalCount[0]);
                      LOGGER.info("Total records pushed to Elasticsearch: {}", totalPushed[0]);
                      LOGGER.info("Processing time: {} ms ({} seconds)", totalTime, totalTime / 1000);
                      if (totalCount[0] > 0) {
                        LOGGER.info("Performance: {:.2f} records/sec", (totalCount[0] * 1000.0 / totalTime));
                      }
                      LOGGER.info("================================================================================");
                      respondSuccess(context, context.response(), idRef[0]);
                    })
                .onFailure(err -> {
                  long totalTime = System.currentTimeMillis() - startTime;
                  LOGGER.error("================================================================================");
                  LOGGER.error("DATA IMPORT FAILED FOR ITEM ID: {}", idRef[0]);
                  LOGGER.error("Records read before failure: {}", totalCount[0]);
                  LOGGER.error("Records pushed before failure: {}", totalPushed[0]);
                  LOGGER.error("Failed at: {} ms ({} seconds)", totalTime, totalTime / 1000);
                  LOGGER.error("Error: {}", err.getMessage());
                  LOGGER.error("================================================================================");
                  context.fail(err);
                });
          }
        });

    // Resume parser and request to start reading data
    parser.resume();
    context.request().resume();
  }

  private Future<String> flushBatch(
      RoutingContext context,
      JsonArray currentBatch,
      String id,
      int[] batchBytes,
      JsonArray batch,
      boolean[] failed,
      boolean onSeek) {
    batch.clear();
    batchBytes[0] = 0;
    Future<String> publishFuture =
        onSeek
            ? ngsildDataPublishService.publishDataOnSeekIntoElastic(currentBatch, id)
            : ngsildDataPublishService.publishData(currentBatch, id);
    return publishFuture.onFailure(
        err -> {
          failed[0] = true;
          context.fail(err);
        });
  }

  private void respondSuccess(RoutingContext context, HttpServerResponse response, String id) {
    JsonObject finalResponse = new JsonObject();
    finalResponse.put(DETAIL, "Item Published");
    JsonArray userRoles =
        context.user().principal().getJsonObject("realm_access").getJsonArray("roles");
    String role = userRoles.contains("provider") ? "provider" : "delegate";
    String delegatorId;
    if (role.equalsIgnoreCase("delegate")) {
      delegatorId = context.request().getHeader("did");
    } else {
      delegatorId = context.user().subject();
    }
    AuditLog auditLog =
        DataplaneAuditHelper.createAuditingLogs(
            RoutingContextHelper.getItemMetaData(context),
            id,
            RoutingContextHelper.getRequestPath(context),
            "POST",
            context.user().subject(),
            NGSILD,
            role,
            CREATE,
            context.user().principal().getString("iss"),
            delegatorId,
            0L);
    RoutingContextHelper.setAuditingLog(context, auditLog);
    response
        .putHeader("Content-Type", "application/json")
        .putHeader(HEADER_ALLOW_ORIGIN, "*")
        .putHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        .putHeader("Access-Control-Allow-Methods", "POST")
        .setStatusCode(200)
        .end(finalResponse.encodePrettily());
  }
}
