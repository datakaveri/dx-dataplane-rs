package org.cdpg.dx.rs.latest.factory;

import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.latest.controller.LatestController;
import org.cdpg.dx.rs.latest.service.LatestService;
import org.cdpg.dx.rs.latest.service.LatestServiceImpl;

public class LatestControllerFactory {

  public static LatestController create(
      SearchService searchService, String timeLimit, String controlPlaneDomain, URNGenerator urnGenerator) {

    LatestService latestService = new LatestServiceImpl(searchService, timeLimit);

    return new LatestController(latestService, controlPlaneDomain, urnGenerator);
  }
}
