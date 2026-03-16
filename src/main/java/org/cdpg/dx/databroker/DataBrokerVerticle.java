package org.cdpg.dx.databroker;

import io.vertx.rabbitmq.RabbitMQClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.databroker.verticle.BaseDataBrokerVerticle;

/**
 * Dataplane-RS DataBrokerVerticle — sets up RS-specific publish exchange.
 */
public class DataBrokerVerticle extends BaseDataBrokerVerticle {

  private static final Logger LOGGER = LogManager.getLogger(DataBrokerVerticle.class);

  @Override
  protected void onBrokerReady(
      RabbitMQClient internalClient, RabbitMQClient prodClient, RabbitClient rabbitClient) {

    // RSP (Real-time Streaming Protocol) publish exchange
    RabbitClient.publishEx = config().getString("adapterQueryPublishExchange");

    LOGGER.info("DataBrokerVerticle: RS broker ready, publishEx={}", RabbitClient.publishEx);
  }
}
