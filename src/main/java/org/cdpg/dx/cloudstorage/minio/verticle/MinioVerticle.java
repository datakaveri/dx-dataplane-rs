package org.cdpg.dx.cloudstorage.minio.verticle;

import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.MINIO_SERVICE_ADDRESS;

import io.minio.MinioClient;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.cloudstorage.minio.service.MinioService;
import org.cdpg.dx.cloudstorage.minio.service.MinioServiceImpl;

public class MinioVerticle extends AbstractVerticle {
  private static final Logger LOGGER = LogManager.getLogger(MinioVerticle.class);

  private ServiceBinder binder;
  private MessageConsumer<JsonObject> consumer;

  @Override
  public void start(Promise<Void> startPromise) {
    String endpoint = config().getString("minioEndpoint");
    String accessKey = config().getString("minioAccessKey");
    String secretKey = config().getString("minioSecretKey");
    String bucket = config().getString("minioBucket");
    String region = config().getString("minioRegion", null);
    int presignedExpirySeconds = config().getInteger("minioUrlExpirySeconds", 3600);
    LOGGER.info("MinIO config endpoint={} bucket={} region={}", endpoint, bucket, region);

    MinioClient.Builder builder =
        MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey);
    if (region != null && !region.isBlank()) {
      builder.region(region);
    }
    MinioClient client = builder.build();

    MinioService minioService = new MinioServiceImpl(vertx, client, bucket, presignedExpirySeconds);

    binder = new ServiceBinder(vertx);
    consumer = binder.setAddress(MINIO_SERVICE_ADDRESS).register(MinioService.class, minioService);

    startPromise.complete();
  }

  @Override
  public void stop() {
    if (binder != null && consumer != null) {
      binder.unregister(consumer);
    }
  }
}
