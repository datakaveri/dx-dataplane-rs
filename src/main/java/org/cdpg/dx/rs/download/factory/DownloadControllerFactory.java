package org.cdpg.dx.rs.download.factory;

import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.database.redis.service.RedisService;
import org.cdpg.dx.rs.download.controller.DownloadController;
import org.cdpg.dx.rs.download.service.DownloadService;
import org.cdpg.dx.rs.download.service.DownloadServiceImpl;

public class DownloadControllerFactory {
  public static DownloadController create(
      String timeLimit,
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      ElasticsearchService elasticsearchService,
      AuditingHandler auditingHandler,
      AppIdItemAccessHandler appIdItemAccessHandler/*,
      RedisService redisService,
      String redisKeyPrefix*/) {
    DownloadService downloadService = new DownloadServiceImpl(timeLimit, elasticsearchService);

    return new DownloadController(
        downloadService,
        controlPlaneDomain,
        urnGenerator,
        auditingHandler,
        appIdItemAccessHandler/*,
        redisService,
        redisKeyPrefix*/);
  }
}
