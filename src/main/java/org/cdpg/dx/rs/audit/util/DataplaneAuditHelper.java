package org.cdpg.dx.rs.audit.util;

import static org.cdpg.dx.rs.audit.util.Constants.ITEM_TYPES;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.cdpg.dx.auditing.model.AuditLog;
import org.cdpg.dx.rs.audit.model.DataPlaneAuditLog;

public class DataplaneAuditHelper {
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
      String delegateId) {
    UUID id = UUID.randomUUID();
    return new DataPlaneAuditLog(
        id,
        UUID.fromString(assetId),
        "ASSET",
        operation,
        LocalDateTime.now().toString(),
        apiEndpoint,
        method,
        0L,
        role,
        UUID.fromString(userId),
        serverName,
        getOrganizationId(itemMetaData),
        getOrganizationName(itemMetaData),
        iss,
        delegateId);
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
