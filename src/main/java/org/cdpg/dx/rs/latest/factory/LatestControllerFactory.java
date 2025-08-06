package org.cdpg.dx.rs.latest.factory;

import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.latest.controller.LatestController;
import org.cdpg.dx.rs.latest.service.LatestService;
import org.cdpg.dx.rs.latest.service.LatestServiceImpl;

public class LatestControllerFactory {

  public static LatestController create(
          String tenantPrefix,
          SearchService searchService, String timeLimit) {

    LatestService latestService =
        new LatestServiceImpl(tenantPrefix, searchService, timeLimit);

    return new LatestController(latestService);
  }
}
