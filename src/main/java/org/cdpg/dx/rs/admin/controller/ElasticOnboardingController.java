package org.cdpg.dx.rs.admin.controller;

import static org.cdpg.dx.apiserver.config.ApiConstants.*;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.apiserver.ApiController;
import org.cdpg.dx.common.response.ResponseBuilder;
import org.cdpg.dx.rs.admin.service.OnboardingService;

public class ElasticOnboardingController implements ApiController {
  private static final Logger LOGGER = LogManager.getLogger(ElasticOnboardingController.class);
  private final OnboardingService onboardingService;
  private final String tenantPrefix;

  public ElasticOnboardingController(OnboardingService onboardingService,String tenantPrefix) {
    this.onboardingService = onboardingService;
    this.tenantPrefix=tenantPrefix;
  }

  @Override
  public void register(RouterBuilder builder) {
    builder.operation(ONBOARD_ELASTICSEARCH_INDEX).handler(this::handleOnboard);
  }

  private void handleOnboard(RoutingContext ctx) {
    try {
      JsonObject body = ctx.body().asJsonObject();
      String resourceId = body.getString("id");

      JsonObject dataDescriptor = body.getJsonObject("dataDescriptor");
      
      // prefer dataset id; fallback to explicit indexName
      if ((resourceId == null || resourceId.isBlank())) {
        ctx.fail(400);
        return;
      }
      if (dataDescriptor == null) {
        ctx.fail(400);
        return;
      }

      onboardingService
          .createDatasetIndex(resourceId, dataDescriptor)
          .onSuccess(
              v -> {
                ResponseBuilder.sendCreated(ctx,  "Index created successfully");
              })
          .onFailure(ctx::fail);
    } catch (Exception e) {
      LOGGER.error("Error in onboarding handler", e);
      ctx.fail(e);
    }
  }


}


