package org.cdpg.dx.rs.ngsild.temporal.factory;

import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.ngsild.temporal.controller.NGSILDSearchController;
import org.cdpg.dx.rs.ngsild.temporal.service.NGSILDService;
import org.cdpg.dx.rs.ngsild.temporal.service.NGSILDServiceImpl;

public class TemporalControllerFactory {
  public static NGSILDSearchController create(
      SearchService searchService,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      int maxDaysSync,
      int maxDaysAsync,
      AuditingHandler auditingHandler) {
    NGSILDService NGSILDService = new NGSILDServiceImpl(searchService);
    return new NGSILDSearchController(
        NGSILDService,
        controlPlaneDomain,
        urnGenerator,
        maxDaysSync,
        maxDaysAsync,
        auditingHandler);
  }
}
