package org.cdpg.dx.common.publicKeyService;

import io.vertx.core.json.JsonObject;

public interface PublicService {

  JsonObject generateJwks();

  String getKid();
}
