package org.cdpg.dx.rs.ngsilddatapublish.factory;

import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.cloudstorage.minio.service.MinioService;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.rs.ngsilddatapublish.controller.NGSILDDataPublishController;
import org.cdpg.dx.rs.ngsilddatapublish.service.NGSILDDataPublishService;
import org.cdpg.dx.rs.ngsilddatapublish.service.NGSILDDataPublishServiceImpl;

public class NGSILDDataPublishFactory {
  public static NGSILDDataPublishController create(
      String controlPlaneDomain,
      URNGenerator urnGenerator,
      AuditingHandler auditingHandler,
      DataBrokerService dataBrokerService,
      ElasticsearchService elasticsearchService,
      MinioService minioService,
      int chunkMaxItems,
      int chunkMaxBytes) {
    NGSILDDataPublishService ngsildDataPublishService =
        new NGSILDDataPublishServiceImpl(
            dataBrokerService, elasticsearchService, minioService);
    return new NGSILDDataPublishController(
        ngsildDataPublishService,
        controlPlaneDomain,
        urnGenerator,
        auditingHandler,
        chunkMaxItems,
        chunkMaxBytes);
  }
}
