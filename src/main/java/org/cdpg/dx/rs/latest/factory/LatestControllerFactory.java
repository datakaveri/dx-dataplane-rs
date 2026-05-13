package org.cdpg.dx.rs.latest.factory;

import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auth.v2.factory.AuthHandlersV2;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.essearch.service.SearchService;
import org.cdpg.dx.rs.latest.controller.LatestController;
import org.cdpg.dx.rs.latest.service.LatestService;
import org.cdpg.dx.rs.latest.service.LatestServiceImpl;

public class LatestControllerFactory {

  public static LatestController create(
      SearchService searchService,
      String timeLimit,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      AuditingHandler auditingHandler,
      AppIdItemAccessHandler appIdItemAccessHandler,
      AuthHandlersV2 authV2) {

    LatestService latestService = new LatestServiceImpl(searchService, timeLimit);

    return new LatestController(
        latestService,
        controlPlaneDomain,
        urnGenerator,
        auditingHandler,
        appIdItemAccessHandler,
        authV2.authorization());
  }
}
