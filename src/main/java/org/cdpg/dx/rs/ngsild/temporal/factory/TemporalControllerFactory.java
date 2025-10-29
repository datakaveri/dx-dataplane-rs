package org.cdpg.dx.rs.ngsild.temporal.factory;

import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.ngsild.temporal.controller.TemporalController;
import org.cdpg.dx.rs.ngsild.temporal.service.TemporalService;
import org.cdpg.dx.rs.ngsild.temporal.service.TemporalServiceImpl;

public class TemporalControllerFactory {
  public static TemporalController create(SearchService searchService) {
    TemporalService temporalService = new TemporalServiceImpl(searchService);
    return new TemporalController(temporalService);
  }
}
