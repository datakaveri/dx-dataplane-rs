package org.cdpg.dx.cloudstorage.s3.verticle;

import static org.cdpg.dx.common.config.ServiceProxyAddressConstants.S3_SERVICE_ADDRESS;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.serviceproxy.ServiceBinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.cloudstorage.s3.service.S3FileService;
import org.cdpg.dx.cloudstorage.s3.service.S3FileServiceImpl;
import org.cdpg.dx.cloudstorage.util.S3FileOpsHelper;

public class S3Verticle extends AbstractVerticle {
  private static final Logger LOGGER = LogManager.getLogger(S3Verticle.class);

  private ServiceBinder binder;
  private MessageConsumer<JsonObject> consumer;

  @Override
  public void start(Promise<Void> startPromise) {
    JsonObject cfg = config();
    String endpoint = cfg.getString("s3Endpoint", null);
    String accessKey = cfg.getString("s3AccessKey", null);
    String secretKey = cfg.getString("s3SecretKey", null);
    String bucket = cfg.getString("s3Bucket", null);
    String region = cfg.getString("s3Region", null);

    LOGGER.info("S3 config endpoint={} bucket={} region={}", endpoint, bucket, region);

    S3FileOpsHelper s3FileOpsHelper =
        new S3FileOpsHelper(endpoint, region, accessKey, secretKey, bucket);
    S3FileService s3FileService = new S3FileServiceImpl(s3FileOpsHelper);

    binder = new ServiceBinder(vertx);
    consumer = binder.setAddress(S3_SERVICE_ADDRESS).register(S3FileService.class, s3FileService);

    startPromise.complete();
  }

  @Override
  public void stop() {
    if (binder != null && consumer != null) {
      binder.unregister(consumer);
    }
  }
}
