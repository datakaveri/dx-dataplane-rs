package org.cdpg.dx.rs.audit.util;

import static org.cdpg.dx.rs.audit.util.Constants.ITEM_TYPES;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.cdpg.dx.auditing.model.AuditLog;
import org.cdpg.dx.common.util.RoutingContextHelper;
import org.cdpg.dx.keycloak.config.KeycloakConstants;
import org.cdpg.dx.rs.audit.model.DataPlaneAuditLog;

public class DataplaneAuditHelper {

  public static final String ACTOR_TYPE_SELF = "SELF";
  public static final String ACTOR_TYPE_DELEGATE = "DELEGATE";
  public static final String ACTOR_TYPE_APP = "APP";

  /**
   * Who actually performed the action. Delegation takes precedence (an AppId request with a did
   * header resolves to the delegator, and the principal no longer carries app_id), then app
   * credentials, defaulting to SELF.
   */
  private static String resolveActorType(JsonObject principal, String delegateId) {
    if (delegateId != null) return ACTOR_TYPE_DELEGATE;
    if (principal.getString(KeycloakConstants.CLAIM_APP_ID) != null) return ACTOR_TYPE_APP;
    return ACTOR_TYPE_SELF;
  }

  public static AuditLog createAuditingLogs(
      RoutingContext ctx,
      String assetId,
      String method,
      String serverName,
      String operation,
      long byteWritten) {
    JsonObject principal = ctx.user().principal();
    String userId = principal.getString("sub");
    String iss = principal.getString("iss");
    String role = resolveRole(principal);
    // After header-based delegation (did), AuthenticationHandler swaps sub to the delegator and
    // stamps the original caller (token sub) as delegatee_sub. Same contract as controlplane's
    // AuditContextExtractor.getDelegateId/getActorType.
    String delegateId = principal.getString(KeycloakConstants.CLAIM_DELEGATEE_SUB);
    String actorType = resolveActorType(principal, delegateId);
    return createAuditingLogs(
        RoutingContextHelper.getItemMetaData(ctx),
        assetId,
        RoutingContextHelper.getRequestPath(ctx),
        method,
        userId,
        serverName,
        role,
        operation,
        iss,
        delegateId,
        actorType,
        byteWritten);
  }

  private static String resolveRole(JsonObject principal) {
    if (principal.getString("app_id") != null) return "consumer";
    JsonArray roles = principal
        .getJsonObject("realm_access", new JsonObject())
        .getJsonArray("roles", new JsonArray());
    if (roles.contains("delegate")) return "delegate";
    if (roles.contains("provider")) return "provider";
    return "consumer";
  }

  public static AuditLog createAuditingLogs(
      JsonObject itemMetaData,
      String assetId,
      String apiEndpoint,
      String method,
      String userId,
      String serverName,
      String role,
      String operation,
      String iss,
      String delegateId,
      String actorType,
      long byteWritten) {
    UUID id = UUID.randomUUID();
    return new DataPlaneAuditLog(
        id,
        UUID.fromString(assetId),
        "ASSET",
        operation,
        LocalDateTime.now().toString(),
        apiEndpoint,
        method,
        byteWritten,
        role,
        UUID.fromString(userId),
        serverName,
        getOrganizationId(itemMetaData),
        getOrganizationName(itemMetaData),
        iss,
        delegateId,
        actorType);
  }

  private static String getItemType(JsonArray itemType) {
    try {
      if (itemType == null || itemType.isEmpty()) {
        return null;
      }

      // Convert JsonArray to Set<String>
      Set<String> types =
          itemType.stream()
              .filter(Objects::nonNull)
              .map(Object::toString)
              .collect(Collectors.toSet());

      // Retain only allowed ITEM_TYPES
      types.retainAll(ITEM_TYPES);

      if (types.isEmpty()) {
        return null;
      }

      return String.join(",", types);
    } catch (Exception e) {
      return null;
    }
  }

  private static String getOrganizationId(JsonObject itemMetaData) {
    String orgId = itemMetaData.getString("organizationId");
    if (orgId == null || orgId.isEmpty()) {
      return null;
    }
    return orgId;
  }

  private static String getOrganizationName(JsonObject itemMetaData) {
    String orgName = itemMetaData.getString("department");
    if (orgName == null || orgName.isEmpty()) {
      return null;
    }
    return orgName;
  }

  private static String getShortDescription(JsonObject itemMetaData) {
    String shortDescription = itemMetaData.getString("shortDescription");
    if (shortDescription == null || shortDescription.isEmpty()) {
      return null;
    }
    return shortDescription;
  }
}
