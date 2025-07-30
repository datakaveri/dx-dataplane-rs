package org.cdpg.dx.apiserver;

import io.vertx.ext.web.openapi.RouterBuilder;

public interface ApiController {
  void register(RouterBuilder builder);
}
