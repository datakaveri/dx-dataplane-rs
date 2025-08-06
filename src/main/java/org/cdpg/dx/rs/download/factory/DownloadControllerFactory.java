package org.cdpg.dx.rs.download.factory;

import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.download.controller.DownloadController;
import org.cdpg.dx.rs.download.service.DownloadService;
import org.cdpg.dx.rs.download.service.DownloadServiceImpl;

public class DownloadControllerFactory {
  public static DownloadController create(
      String tenantPrefix, SearchService searchService, String timeLimit) {
      DownloadService downloadService =
          new DownloadServiceImpl(searchService, tenantPrefix, timeLimit);

      return new DownloadController(downloadService);
  }
}
