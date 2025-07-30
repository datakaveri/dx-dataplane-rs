package org.cdpg.dx.rs.authorization.service;

import io.vertx.core.Future;
import org.cdpg.dx.common.model.JwtData;


public interface ResourcePolicyAuthorizationServiceImpl {
  Future<Void> authorize(JwtData JwtData, String resourceId);
}
