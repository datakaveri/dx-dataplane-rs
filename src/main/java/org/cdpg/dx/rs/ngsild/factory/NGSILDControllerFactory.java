package org.cdpg.dx.rs.ngsild.factory;

import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auth.v2.factory.AuthHandlersV2;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.ngsild.controller.NGSILDSearchController;
import org.cdpg.dx.rs.ngsild.service.NGSILDService;
import org.cdpg.dx.rs.ngsild.service.NGSILDServiceImpl;

public class NGSILDControllerFactory {
  public static NGSILDSearchController create(
      SearchService searchService,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      int maxDaysSync,
      int maxDaysAsync,
      AuditingHandler auditingHandler,
      AppIdItemAccessHandler appIdItemAccessHandler,
      AuthHandlersV2 authV2) {
    NGSILDService ngsildService = new NGSILDServiceImpl(searchService);
    return new NGSILDSearchController(
        ngsildService,
        controlPlaneDomain,
        urnGenerator,
        maxDaysSync,
        maxDaysAsync,
        auditingHandler,
        appIdItemAccessHandler,
        authV2.authorization());
  }
}
