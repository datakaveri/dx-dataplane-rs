package org.cdpg.dx.apiserver;

import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.DATA_BROKER_SERVICE_ADDRESS;
import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.ELASTIC_SERVICE_ADDRESS;
import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.MINIO_SERVICE_ADDRESS;
import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.S3_SERVICE_ADDRESS;
import static org.cdpg.dx.rs.rsp.entities.controller.config.DEFAULT_AUDITING_EXCHANGE;
import static org.cdpg.dx.rs.rsp.entities.controller.config.DEFAULT_AUDITING_ROUTING_KEY;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.cdpg.dx.auditing.handler.AuditingHandler;
import org.cdpg.dx.auth.appid.AppIdItemAccessHandler;
import org.cdpg.dx.auth.v2.factory.AuthHandlersV2;
import org.cdpg.dx.cloudstorage.minio.service.MinioService;
import org.cdpg.dx.cloudstorage.s3.service.S3FileService;
import org.cdpg.dx.common.URNGenerator;
import org.cdpg.dx.database.elastic.service.ElasticsearchService;
import org.cdpg.dx.databroker.service.DataBrokerService;
import org.cdpg.dx.rs.ngsilddatapublish.controller.NGSILDDataPublishController;
import org.cdpg.dx.rs.ngsilddatapublish.factory.NGSILDDataPublishFactory;

public class PublishedControllerFactory {
  private PublishedControllerFactory() {}

  public static List<ApiController> createControllers(
      Vertx vertx,
      JsonObject config,
      URNGenerator urnGenerator,
      AppIdItemAccessHandler appIdItemAccessHandler,
      AuthHandlersV2 authV2) {
    DataBrokerService dataBrokerService =
        DataBrokerService.createProxy(vertx, DATA_BROKER_SERVICE_ADDRESS);
    ElasticsearchService elasticsearchService =
        ElasticsearchService.createProxy(vertx, ELASTIC_SERVICE_ADDRESS);
    long minioProxyTimeoutMs = config.getLong("minioProxyTimeoutMs", 180000L);
    MinioService minioService =
        MinioService.createProxy(vertx, MINIO_SERVICE_ADDRESS, minioProxyTimeoutMs);
    long s3ProxyTimeoutMs = config.getLong("s3ProxyTimeoutMs", 240000L);
    S3FileService s3FileService =
        S3FileService.createProxy(vertx, S3_SERVICE_ADDRESS, s3ProxyTimeoutMs);
    AuditingHandler auditingHandler =
        new AuditingHandler(
            dataBrokerService,
            config.getString("auditingExchange", DEFAULT_AUDITING_EXCHANGE),
            config.getString("auditingRoutingKey", DEFAULT_AUDITING_ROUTING_KEY));
    int chunkMaxItems = config.getInteger("chunkMaxItems", 2000);
    int chunkMaxBytes = config.getInteger("chunkMaxBytes", 5);

    NGSILDDataPublishController publishController =
        NGSILDDataPublishFactory.create(
            config.getString("controlPlaneDomain"),
            urnGenerator,
            auditingHandler,
            dataBrokerService,
            elasticsearchService,
            minioService,
            appIdItemAccessHandler,
            authV2,
            chunkMaxItems,
            chunkMaxBytes,
            s3FileService);

    return List.of(publishController);
  }
}
