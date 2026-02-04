package org.cdpg.dx.rs.audit.model;

import io.vertx.core.json.JsonObject;
import java.util.UUID;
import org.cdpg.dx.auditing.model.AuditLog;

public class DataPlaneAuditLog implements AuditLog {
  private final UUID id;
  private final UUID assetId;
  private final String logType;
  private final String action;
  private final String createdAt;
  private final String api;
  private final String method;
  private final Long size;
  private final String role;
  private final UUID userId;
  private final String originServer;
  private final String iss;
  private final String delegateId;
  private final String
      organizationId; // Optional field  in auditing server [organization id of consumer, provider].
  // Can be null sometimes
  private final String
      organizationName; // Optional field in auditing server [organization name of consumer,

  // provider]. Can be null sometimes

  // change this to AuditingHandler and it can be a helper
  public DataPlaneAuditLog(
      UUID id,
      UUID assetId,
      String logType,
      String operation,
      String createdAt,
      String api,
      String method,
      Long size,
      String role,
      UUID userId,
      String originServer,
      String organizationId,
      String organizationName,
      String iss,
      String delegateId) {
    this.id = id;
    this.assetId = assetId;
    this.logType = logType;
    this.action = operation;
    this.createdAt = createdAt;
    this.api = api;
    this.method = method;
    this.size = size;
    this.role = role;
    this.userId = userId;
    this.originServer = originServer;
    this.organizationId = organizationId;
    this.organizationName = organizationName;
    this.iss = iss;
    this.delegateId = delegateId;
  }

  @Override
  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    json.put("id", id.toString());
    json.put("asset_id", assetId.toString());
    json.put("action", action);
    json.put("created_at", createdAt);
    json.put("api", api);
    json.put("method", method);
    json.put("size_bytes", size);
    json.put("role", role);
    json.put("user_id", userId.toString());
    json.put("origin_server", originServer);
    json.put("org_id", organizationId);
    json.put("org_name", organizationName);
    json.put("log_type", logType);
    json.put("issuer", iss);
    json.put("delegator_id", delegateId);
    return json;
  }
}
