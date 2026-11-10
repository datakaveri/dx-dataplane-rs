package org.cdpg.dx.apiserver;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.common.exception.DxBadRequestException;

/**
 * Simple file upload endpoint controller.
 * Handles binary file uploads and returns a file ID for later use.
 */
public class FileUploadController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(FileUploadController.class);

  @Override
  public void register(RouterBuilder builder) {
    // Register upload endpoint
    // Note: Routes are configured in AbstractApiServerVerticle.configureAdditionalRoutes()
  }

  public void handleUpload(RoutingContext context) {
    try {
      // Get the request body (binary data)
      byte[] fileData = context.getBody().getBytes();
      
      if (fileData.length == 0) {
        context.fail(new DxBadRequestException("Empty file"));
        return;
      }

      // Store the file and get ID
      String fileId = FileUploadManager.storeFile(fileData);
      long fileSize = fileData.length;

      LOGGER.info("File uploaded successfully. ID: {}, Size: {} bytes", fileId, fileSize);

      // Return the file ID to client
      JsonObject response = new JsonObject();
      response.put("fileId", fileId);
      response.put("size", fileSize);
      response.put("message", "File uploaded successfully");

      context.response()
          .putHeader("Content-Type", "application/json")
          .setStatusCode(200)
          .end(response.encodePrettily());

    } catch (Exception e) {
      LOGGER.error("Error uploading file", e);
      context.fail(new DxBadRequestException("File upload failed: " + e.getMessage()));
    }
  }

  public void handleDelete(RoutingContext context) {
    try {
      String fileId = context.pathParam("fileId");
      
      if (fileId == null || fileId.isEmpty()) {
        context.fail(new DxBadRequestException("File ID is required"));
        return;
      }

      long fileSize = FileUploadManager.getFileSize(fileId);
      if (fileSize == 0) {
        context.fail(new DxBadRequestException("File not found: " + fileId));
        return;
      }

      FileUploadManager.deleteFile(fileId);

      JsonObject response = new JsonObject();
      response.put("message", "File deleted successfully");
      response.put("fileId", fileId);

      context.response()
          .putHeader("Content-Type", "application/json")
          .setStatusCode(200)
          .end(response.encodePrettily());

    } catch (Exception e) {
      LOGGER.error("Error deleting file", e);
      context.fail(new DxBadRequestException("File deletion failed: " + e.getMessage()));
    }
  }
}

