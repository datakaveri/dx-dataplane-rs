package org.cdpg.dx.apiserver;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * File upload manager with disk-based storage for large files.
 * Small files (< 100MB) stored in memory, large files stored on disk.
 * Automatic cleanup via TTL.
 */
public class FileUploadManager {
  private static final Logger LOGGER = LogManager.getLogger(FileUploadManager.class);
  private static final Map<String, FileMetadata> uploadedFiles = new HashMap<>();
  
  // Configuration
  private static final long FILE_TTL_MS = 3600000; // 1 hour TTL
  private static final long MAX_MEMORY_FILE_SIZE = 104857600; // 100MB - in-memory threshold
  private static final long MAX_FILE_SIZE = 5368709120L; // 5GB - absolute max
  private static final long MAX_TOTAL_MEMORY = 2073741824L; // 2GB - memory limit
  private static final long MAX_DISK_STORAGE = 107374182400L; // 100GB - disk limit
  private static final long CLEANUP_INTERVAL_MS = 300000; // Cleanup every 5 minutes
  
  private static final Path TEMP_DIR;
  private static long totalMemoryUsed = 0;
  private static long totalDiskUsed = 0;
  private static long lastCleanupTime = System.currentTimeMillis();

  static {
    // Initialize temp directory
    try {
      String tempPath = System.getProperty("java.io.tmpdir") + File.separator + "dx-dataplane-uploads";
      TEMP_DIR = Paths.get(tempPath);
      Files.createDirectories(TEMP_DIR);
      LOGGER.info("Temp directory initialized: {}", TEMP_DIR);
    } catch (IOException e) {
      throw new RuntimeException("Failed to initialize temp directory", e);
    }
  }

  /**
   * Metadata for stored files
   */
  private static class FileMetadata {
    byte[] memoryData;      // For small files (< 100MB)
    Path diskPath;          // For large files (>= 100MB)
    long timestamp;
    long size;
    boolean isOnDisk;

    FileMetadata(byte[] data, boolean onDisk) {
      this.timestamp = System.currentTimeMillis();
      this.size = data.length;
      this.isOnDisk = onDisk;
      
      if (onDisk) {
        // Write to disk
        try {
          String filename = UUID.randomUUID() + ".tmp";
          this.diskPath = TEMP_DIR.resolve(filename);
          Files.write(diskPath, data);
          LOGGER.debug("File written to disk: {}", diskPath);
        } catch (IOException e) {
          LOGGER.error("Failed to write file to disk", e);
          throw new RuntimeException("Disk write failed", e);
        }
      } else {
        this.memoryData = data;
      }
    }

    boolean isExpired() {
      return (System.currentTimeMillis() - timestamp) > FILE_TTL_MS;
    }

    byte[] getData() {
      if (isOnDisk) {
        try {
          return Files.readAllBytes(diskPath);
        } catch (IOException e) {
          LOGGER.error("Failed to read file from disk: {}", diskPath, e);
          return null;
        }
      } else {
        return memoryData;
      }
    }

    void cleanup() {
      if (isOnDisk && diskPath != null) {
        try {
          Files.deleteIfExists(diskPath);
          LOGGER.debug("Deleted disk file: {}", diskPath);
        } catch (IOException e) {
          LOGGER.warn("Failed to delete disk file: {}", diskPath, e);
        }
      }
    }
  }

  /**
   * Store uploaded file data with smart storage (memory or disk)
   * 
   * @param data the file content
   * @return unique file ID or null if storage limit exceeded
   */
  public static String storeFile(byte[] data) {
    // Check file size limit
    if (data.length > MAX_FILE_SIZE) {
      LOGGER.error("File size {} bytes ({} MB) exceeds maximum allowed {} bytes ({} MB)", 
                   data.length, data.length / (1024 * 1024),
                   MAX_FILE_SIZE, MAX_FILE_SIZE / (1024 * 1024));
      return null;
    }

    // Perform periodic cleanup
    long currentTime = System.currentTimeMillis();
    if (currentTime - lastCleanupTime > CLEANUP_INTERVAL_MS) {
      cleanupExpiredFiles();
      lastCleanupTime = currentTime;
    }

    // Decide storage location
    boolean useDisK = data.length > MAX_MEMORY_FILE_SIZE;
    
    if (useDisK) {
      // Check disk storage limit
      if (totalDiskUsed + data.length > MAX_DISK_STORAGE) {
        LOGGER.warn("Disk storage limit would be exceeded. Current: {} bytes, Required: {} bytes, Limit: {} bytes",
                    totalDiskUsed, totalDiskUsed + data.length, MAX_DISK_STORAGE);
        cleanupExpiredFiles();
        
        if (totalDiskUsed + data.length > MAX_DISK_STORAGE) {
          LOGGER.error("Insufficient disk storage even after cleanup");
          return null;
        }
      }
    } else {
      // Check memory storage limit
      long requiredMemory = totalMemoryUsed + data.length;
      if (requiredMemory > MAX_TOTAL_MEMORY) {
        LOGGER.warn("Memory storage limit would be exceeded. Current: {} bytes, Required: {} bytes, Limit: {} bytes",
                    totalMemoryUsed, requiredMemory, MAX_TOTAL_MEMORY);
        cleanupExpiredFiles();
        
        if (totalMemoryUsed + data.length > MAX_TOTAL_MEMORY) {
          // Try disk storage instead
          LOGGER.info("Memory limit exceeded, storing on disk instead");
          useDisK = true;
        }
      }
    }

    String fileId = UUID.randomUUID().toString();
    try {
      FileMetadata metadata = new FileMetadata(data, useDisK);
      uploadedFiles.put(fileId, metadata);
      
      if (useDisK) {
        totalDiskUsed += data.length;
        LOGGER.info("File stored on DISK with ID: {}, Size: {} bytes ({} MB), Total disk: {}/{} bytes ({:.1f}%)", 
                    fileId, data.length, data.length / (1024 * 1024),
                    totalDiskUsed, MAX_DISK_STORAGE,
                    (totalDiskUsed * 100.0 / MAX_DISK_STORAGE));
      } else {
        totalMemoryUsed += data.length;
        LOGGER.info("File stored in MEMORY with ID: {}, Size: {} bytes ({} MB), Total memory: {}/{} bytes ({:.1f}%)", 
                    fileId, data.length, data.length / (1024 * 1024),
                    totalMemoryUsed, MAX_TOTAL_MEMORY,
                    (totalMemoryUsed * 100.0 / MAX_TOTAL_MEMORY));
      }

      // Log warnings if approaching limits
      if (totalMemoryUsed > MAX_TOTAL_MEMORY * 0.8) {
        LOGGER.warn("Memory storage usage is at {:.1f}% of maximum", 
                    (totalMemoryUsed * 100.0 / MAX_TOTAL_MEMORY));
      }
      if (totalDiskUsed > MAX_DISK_STORAGE * 0.8) {
        LOGGER.warn("Disk storage usage is at {:.1f}% of maximum", 
                    (totalDiskUsed * 100.0 / MAX_DISK_STORAGE));
      }

      return fileId;
    } catch (Exception e) {
      LOGGER.error("Failed to store file", e);
      return null;
    }
  }

  /**
   * Get file path for disk-based files (for streaming without loading into memory)
   * 
   * @param fileId the file ID
   * @return file path for disk files, or null for memory files or if not found
   */
  public static Path getFilePath(String fileId) {
    FileMetadata metadata = uploadedFiles.get(fileId);
    if (metadata == null) {
      LOGGER.warn("File not found: {}", fileId);
      return null;
    }

    if (metadata.isExpired()) {
      LOGGER.info("File has expired: {}, Size: {} bytes", fileId, metadata.size);
      deleteFile(fileId);
      return null;
    }

    if (metadata.isOnDisk && metadata.diskPath != null) {
      LOGGER.info("Retrieved file path (DISK) - fileId: {}, Path: {}, Size: {} bytes ({} MB), Age: {} seconds", 
                  fileId, metadata.diskPath, metadata.size, metadata.size / (1024 * 1024),
                  (System.currentTimeMillis() - metadata.timestamp) / 1000);
      return metadata.diskPath;
    }

    return null;  // Memory files are not streamed from path
  }

  /**
   * Get file data for memory files only (small files)
   * For disk files, use getFilePath() instead for streaming
   * 
   * @param fileId the file ID
   * @return file content for memory files, or null
   */
  public static byte[] getFileData(String fileId) {
    FileMetadata metadata = uploadedFiles.get(fileId);
    if (metadata == null) {
      LOGGER.warn("File not found: {}", fileId);
      return null;
    }

    if (metadata.isExpired()) {
      LOGGER.info("File has expired: {}, Size: {} bytes", fileId, metadata.size);
      deleteFile(fileId);
      return null;
    }

    if (!metadata.isOnDisk && metadata.memoryData != null) {
      LOGGER.info("Retrieved file data (MEMORY) - fileId: {}, Size: {} bytes ({} MB), Age: {} seconds", 
                  fileId, metadata.size, metadata.size / (1024 * 1024),
                  (System.currentTimeMillis() - metadata.timestamp) / 1000);
      return metadata.memoryData;
    }

    return null;  // Disk files should be streamed using path, not data
  }

  /**
   * Delete stored file
   * 
   * @param fileId the file ID
   */
  public static void deleteFile(String fileId) {
    FileMetadata metadata = uploadedFiles.remove(fileId);
    if (metadata != null) {
      metadata.cleanup();
      
      if (metadata.isOnDisk) {
        totalDiskUsed -= metadata.size;
        LOGGER.info("Deleted file (DISK): {}, Size: {} bytes ({} MB), Remaining: {}/{} bytes", 
                    fileId, metadata.size, metadata.size / (1024 * 1024),
                    totalDiskUsed, MAX_DISK_STORAGE);
      } else {
        totalMemoryUsed -= metadata.size;
        LOGGER.info("Deleted file (MEMORY): {}, Size: {} bytes ({} MB), Remaining: {}/{} bytes", 
                    fileId, metadata.size, metadata.size / (1024 * 1024),
                    totalMemoryUsed, MAX_TOTAL_MEMORY);
      }
    } else {
      LOGGER.warn("File not found for deletion: {}", fileId);
    }
  }

  /**
   * Get file size
   * 
   * @param fileId the file ID
   * @return file size in bytes, or 0 if not found
   */
  public static long getFileSize(String fileId) {
    FileMetadata metadata = uploadedFiles.get(fileId);
    if (metadata != null && !metadata.isExpired()) {
      return metadata.size;
    }
    return 0;
  }

  /**
   * Clean up expired files
   */
  private static void cleanupExpiredFiles() {
    long cleanupStartTime = System.currentTimeMillis();
    int countBefore = uploadedFiles.size();
    long memoryBefore = totalMemoryUsed;
    long diskBefore = totalDiskUsed;

    Iterator<Map.Entry<String, FileMetadata>> iterator = uploadedFiles.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, FileMetadata> entry = iterator.next();
      FileMetadata metadata = entry.getValue();
      if (metadata.isExpired()) {
        if (metadata.isOnDisk) {
          totalDiskUsed -= metadata.size;
        } else {
          totalMemoryUsed -= metadata.size;
        }
        metadata.cleanup();
        iterator.remove();
      }
    }

    int countAfter = uploadedFiles.size();
    long memoryAfter = totalMemoryUsed;
    long diskAfter = totalDiskUsed;
    long cleanupDuration = System.currentTimeMillis() - cleanupStartTime;

    if (countBefore > countAfter || memoryBefore > memoryAfter || diskBefore > diskAfter) {
      LOGGER.info("Cleanup completed: Removed {} files, Memory freed: {} MB ({:.1f}%), " +
                  "Disk freed: {} MB ({:.1f}%), Duration: {} ms, " +
                  "Remaining - Memory: {}/{} ({:.1f}%), Disk: {}/{} ({:.1f}%)",
                  countBefore - countAfter,
                  (memoryBefore - memoryAfter) / (1024 * 1024),
                  ((memoryBefore - memoryAfter) * 100.0 / MAX_TOTAL_MEMORY),
                  (diskBefore - diskAfter) / (1024 * 1024),
                  ((diskBefore - diskAfter) * 100.0 / MAX_DISK_STORAGE),
                  cleanupDuration,
                  memoryAfter, MAX_TOTAL_MEMORY, (memoryAfter * 100.0 / MAX_TOTAL_MEMORY),
                  diskAfter, MAX_DISK_STORAGE, (diskAfter * 100.0 / MAX_DISK_STORAGE));
    }
  }

  /**
   * Get storage statistics
   */
  public static Map<String, Object> getStorageStats() {
    Map<String, Object> stats = new HashMap<>();
    stats.put("memoryUsed", totalMemoryUsed);
    stats.put("maxMemory", MAX_TOTAL_MEMORY);
    stats.put("memoryPercentage", (totalMemoryUsed * 100.0 / MAX_TOTAL_MEMORY));
    stats.put("diskUsed", totalDiskUsed);
    stats.put("maxDisk", MAX_DISK_STORAGE);
    stats.put("diskPercentage", (totalDiskUsed * 100.0 / MAX_DISK_STORAGE));
    stats.put("filesStored", uploadedFiles.size());
    stats.put("availableMemory", MAX_TOTAL_MEMORY - totalMemoryUsed);
    stats.put("availableDisk", MAX_DISK_STORAGE - totalDiskUsed);
    return stats;
  }

  /**
   * Get total files stored
   */
  public static int getTotalFiles() {
    return uploadedFiles.size();
  }

  /**
   * Get storage usage statistics
   */
  public static long getTotalMemoryUsed() {
    return totalMemoryUsed;
  }

  /**
   * Get total disk usage
   */
  public static long getTotalDiskUsed() {
    return totalDiskUsed;
  }
}

