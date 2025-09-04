package org.cdpg.dx.rs.download.factory;

import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.download.controller.DownloadController;
import org.cdpg.dx.rs.download.service.DownloadService;
import org.cdpg.dx.rs.download.service.DownloadServiceImpl;

public class DownloadControllerFactory {
  public static DownloadController create(SearchService searchService, String timeLimit, String controlPlaneDomain, URNGenerator urnGenerator) {
    DownloadService downloadService = new DownloadServiceImpl(searchService, timeLimit);

    return new DownloadController(downloadService,controlPlaneDomain, urnGenerator);
  }
}
