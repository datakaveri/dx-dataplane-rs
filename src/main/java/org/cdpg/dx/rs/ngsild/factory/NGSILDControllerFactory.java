package org.cdpg.dx.rs.ngsild.factory;

import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.database.redis.service.RedisService;
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
      RedisService redisService) {
    NGSILDService NGSILDService = new NGSILDServiceImpl(searchService);
    return new NGSILDSearchController(
        NGSILDService,
        controlPlaneDomain,
        urnGenerator,
        maxDaysSync,
        maxDaysAsync,
        auditingHandler,
        redisService);
  }
}
