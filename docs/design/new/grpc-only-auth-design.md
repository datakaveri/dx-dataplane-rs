# gRPC-Only Auth Design — AppId + JWT via Pure gRPC

**Version:** 1.0
**Date:** 2026-04-07
**Author:** Ankit Singh
**Status:** Draft — Awaiting Manager Approval
**Reference:** `grpc-external-auth-design.md` (kept for historical context — different approach)

---

## Design Philosophy

This document defines an architecture where **gRPC is used for everything**:

| Communication path | Protocol |
|---|---|
| External client → Dataplane (data queries) | gRPC (port 9090) |
| Dataplane → Controlplane (AppId verify) | gRPC (port 9000) |
| Dataplane → Controlplane (JWT item access) | gRPC (port 9000) |
| Controlplane → Dataplane (revocation push) | gRPC (port 9000, server-streaming) |
| Dataplane HTTP path (existing) | HTTP/REST (port 8443) — **unchanged** |
| HTTP path auth → Controlplane | gRPC (port 9000) — same internal client |

**Why all-gRPC for internal auth calls?**
- Single protocol, single connection pool (no HTTP + gRPC mix)
- HTTP/2 multiplexing: all internal calls share one persistent channel
- Binary Protobuf: no JSON serialization/deserialization overhead on internal calls
- Streaming built-in: `WatchRevocations` is already a stream; `VerifyApp` and `CheckItemAccess` are unary on the same channel
- Strong typing: Protobuf contracts catch mismatches at compile time
- mTLS: one security model for all internal communication, not HTTP TLS + gRPC TLS separately

**What does NOT change:**
- The existing HTTP REST API on port 8443 still exists and works
- JWT is still validated locally on the dataplane (JWKS signature check) — no round-trip for signature
- The fast path (JWT with embedded policies) still requires zero controlplane calls
- `ItemAccessApplicableFilterHandlerNgsild` and all business logic handlers are **not modified**

---

## 1. System Architecture — Full gRPC Picture

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║  EXTERNAL CLIENTS                                                                 ║
║                                                                                   ║
║   IoT Script / App (AppId)        Keycloak User (JWT)                            ║
║         │  x-app-id metadata            │  authorization: Bearer <jwt>           ║
║         │                               │                                        ║
╚═════════╪═══════════════════════════════╪════════════════════════════════════════╝
          │ gRPC (port 9090, TLS)         │ gRPC (port 9090, TLS)
          ▼                               ▼
╔═══════════════════════════════════════════════════════════════════════════════════╗
║  DATAPLANE  (dx-dataplane-rs)                                                     ║
║                                                                                   ║
║  ┌─────────────────────────────────────────────────────────────────────────┐     ║
║  │  External gRPC Server (port 9090)                                       │     ║
║  │  GrpcAuthPipeline ──► GrpcDataServiceImpl ──► GrpcAuditingHandler      │     ║
║  └─────────────────────────────────────────────────────────────────────────┘     ║
║                                                                                   ║
║  ┌─────────────────────────────────────────────────────────────────────────┐     ║
║  │  HTTP Server (port 8443) [existing]                                     │     ║
║  │  AppIdOrJwtAuthHandler ──► Handler chain ──► AuditingHandler           │     ║
║  └─────────────────────────────────────────────────────────────────────────┘     ║
║                                                                                   ║
║  Shared Internal gRPC Clients (used by BOTH HTTP and gRPC paths above):          ║
║    AppVerifyGrpcClient     ──► VerifyApp RPC         ─────┐                      ║
║    CatalogueAccessGrpcClient ► CheckItemAccess RPC   ─────┤ gRPC ch. port 9000  ║
║    AppRevocationStreamHandler ◄ WatchRevocations     ─────┘                      ║
║                                                                                   ║
╚═══════════════════════════════════════════════════════════════════════════════════╝
          │                               │
          │ gRPC channel (port 9000, internal — network-policy restricted)
          ▼
╔═══════════════════════════════════════════════════════════════════════════════════╗
║  CONTROLPLANE                                                                     ║
║                                                                                   ║
║  ┌─────────────────────────────────────────────────────────────────────────┐     ║
║  │  Internal gRPC Server (port 9000)                                       │     ║
║  │                                                                         │     ║
║  │  AppVerifyService.VerifyApp(appId, resourceId)                          │     ║
║  │    → queries app_credentials + app_constraints + catalogue              │     ║
║  │    → returns VerifyAppResponse (userId, role, queryTypes, attrs, ...)   │     ║
║  │                                                                         │     ║
║  │  CatalogueAccessService.CheckItemAccess(userId, roles, resourceId, ...) │     ║
║  │    → queries catalogue + user ACL policies                              │     ║
║  │    → same data as GET /iudx/v2/cat/item/access but via gRPC            │     ║
║  │    → returns CheckItemAccessResponse (queryTypes, attrs, accessPolicy)  │     ║
║  │                                                                         │     ║
║  │  AppRevocationService.WatchRevocations(serviceId)                       │     ║
║  │    → server-streaming: pushes revocation events to all dataplane pods   │     ║
║  └─────────────────────────────────────────────────────────────────────────┘     ║
║                                                                                   ║
║  ┌─────────────────────────────────────────────────────────────────────────┐     ║
║  │  HTTP Server (existing endpoints — unchanged)                           │     ║
║  │  GET /iudx/v2/cat/item/access  (still exists for other consumers)      │     ║
║  └─────────────────────────────────────────────────────────────────────────┘     ║
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

---

## 2. Port Strategy

| Port | Server | Access | Purpose |
|---|---|---|---|
| 8443 | Dataplane HTTP | External (public) | Existing REST API — unchanged |
| 9090 | Dataplane gRPC | External (public, TLS) | **New** — external gRPC for data queries |
| 9000 | Controlplane gRPC | Internal only (network policy) | Auth verification + revocation stream |

Both the HTTP path (port 8443) and gRPC path (port 9090) on the dataplane **use the same
internal gRPC clients** to call the controlplane on port 9000. There is no duplication.

---

## 3. Proto Definitions — All Services

### 3.1 Data Service Proto (External — Dataplane gRPC Server, port 9090)

```protobuf
// data_service.proto
syntax = "proto3";
package dx.data.v1;

option java_package = "org.cdpg.dx.dataplane.grpc";
option java_outer_classname = "DataServiceProto";

service DataService {
    // NGSILD temporal/geo/attr search — equivalent to POST/GET /iudx/v2/entities
    rpc SearchEntities(SearchEntitiesRequest) returns (SearchEntitiesResponse);

    // Latest data for a resource — equivalent to GET /iudx/v2/latest/{id}
    rpc GetLatest(GetLatestRequest) returns (GetLatestResponse);

    // Real-time server-streaming — no HTTP equivalent
    rpc StreamLatest(StreamLatestRequest) returns (stream LatestDataEvent);
}

message SearchEntitiesRequest {
    string id          = 1;   // resource URN: urn:dx:rs:domain/rs/group/item
    string timerel     = 2;   // after | before | between
    string time        = 3;   // ISO8601
    string end_time    = 4;   // ISO8601 (for "between")
    repeated string attrs = 5;
    int32 offset       = 6;
    int32 limit        = 7;
    string georel      = 8;
    string geometry    = 9;
    string coordinates = 10;
}

message SearchEntitiesResponse {
    repeated bytes entities = 1;  // JSON-encoded NGSILD entities (UTF-8 bytes)
    int32 total_hits        = 2;
    string next_page_token  = 3;
}

message GetLatestRequest {
    string id  = 1;
    int32 size = 2;
    int32 page = 3;
}

message GetLatestResponse {
    repeated bytes entities = 1;
    int32 total_hits        = 2;
}

message StreamLatestRequest {
    string id         = 1;   // resource URN
    int64 since_epoch = 2;   // optional: only events after this epoch
}

message LatestDataEvent {
    string resource_id      = 1;
    bytes payload           = 2;   // JSON-encoded entity
    int64 observed_at_epoch = 3;
}
```

> **Why `bytes` for entities?** NGSILD entities are schema-free JSON-LD. Defining typed Protobuf
> messages for them requires knowing every possible attribute across all resource types — not
> feasible in Phase 1. `bytes` (UTF-8 JSON) preserves JSON-LD flexibility with Protobuf framing.

---

### 3.2 AppVerify Proto (Internal — Controlplane gRPC Server, port 9000)

```protobuf
// app_verify.proto
syntax = "proto3";
package dx.auth.v1;

option java_package = "org.cdpg.dx.auth.grpc";
option java_outer_classname = "AppVerifyProto";

service AppVerifyService {
    // Unary: dataplane sends appId + resourceId, controlplane returns full access context.
    // Called by dataplane on cache miss. Response is cached by Caffeine (5-min TTL).
    rpc VerifyApp(VerifyAppRequest) returns (VerifyAppResponse);
}

message VerifyAppRequest {
    string app_id      = 1;   // UUID of the AppId credential
    string resource_id = 2;   // URN of the resource being accessed
    string service_id  = 3;   // caller identifier (e.g., "dx-dataplane-pod-xyz")
}

message VerifyAppResponse {
    // Identity fields — replaces what JWT provides automatically
    string user_id          = 1;   // UUID of the user who owns this AppId
    string issuer           = 2;   // "dx-controlplane"
    repeated string roles   = 3;   // ["consumer"] — AppId is always consumer
    string policy_id        = 4;   // UUID of the policy governing this AppId

    // Access context — same fields as /iudx/v2/cat/item/access result
    string access_policy    = 5;   // "OPEN" | "SECURE" | "PII"
    repeated string query_types = 6;          // ["TEMPORAL", "ATTR", "GEO"]
    repeated string allowed_attributes = 7;  // attribute filter (empty = all allowed)
    repeated AccessEntry access_entries = 8; // accessType + expiry
    string iid              = 9;   // resource URN (echo of request.resource_id)
}

message AccessEntry {
    string access_type = 1;   // "api"
    int64 expiry_epoch = 2;
}
```

> **resource_id in VerifyAppRequest**: Unlike a plain "verify this AppId exists" check, this
> call is per-resource. An AppId may be authorized for multiple resources with different
> constraints. The controlplane returns constraints specific to `resource_id`. The dataplane
> cache key is `appId:resourceId` (not just `appId`).

---

### 3.3 Catalogue Access Proto (Internal — Controlplane gRPC Server, port 9000)

This is the gRPC equivalent of `GET /iudx/v2/cat/item/access`. It replaces the HTTP slow
path for JWT requests.

```protobuf
// catalogue_access.proto
syntax = "proto3";
package dx.auth.v1;

option java_package = "org.cdpg.dx.auth.grpc";
option java_outer_classname = "CatalogueAccessProto";

service CatalogueAccessService {
    // Unary: validate user's access to a resource and return access context.
    // Called by dataplane for JWT requests where policies are NOT embedded in the token.
    // Dataplane validates JWT locally (JWKS) and sends extracted claims — NOT the raw token.
    rpc CheckItemAccess(CheckItemAccessRequest) returns (CheckItemAccessResponse);
}

message CheckItemAccessRequest {
    // Validated JWT claims sent by dataplane (after local JWKS validation)
    // Raw token is NOT forwarded — only extracted, verified claims.
    string user_id          = 1;   // from JWT "sub" claim
    repeated string roles   = 2;   // from JWT "realm_access.roles"
    string issuer           = 3;   // from JWT "iss"

    // Resource being accessed
    string resource_id      = 4;   // resource URN

    // Delegation (optional)
    bool is_delegator       = 5;
    string delegator_id     = 6;   // if is_delegator = true
}

message CheckItemAccessResponse {
    // Same structure as /iudx/v2/cat/item/access result
    string iid              = 1;
    string access_policy    = 2;   // "OPEN" | "SECURE" | "PII"
    repeated ResourceServer resource_servers = 3;
    repeated Policy policies = 4;
    repeated AccessEntry access_entries = 5;
    string policy_id        = 6;
}

message ResourceServer {
    string name             = 1;   // "NGSI-LD"
    repeated string query_types = 2;
}

message Policy {
    string policy_id        = 1;
    PolicyConstraints cons  = 2;
}

message PolicyConstraints {
    repeated string allowed_attributes = 1;
    repeated AccessEntry access        = 2;
}
```

> **Why NOT forward the raw JWT?**
> - Security: the raw token is a bearer credential; forwarding it over an internal channel (even
>   gRPC) expands the attack surface unnecessarily.
> - The dataplane already validates the JWT signature, expiry, and audience locally (JWKS).
>   The validated `sub`, `roles`, and `iss` claims are what the controlplane needs to look up ACL.
> - The controlplane trusts the dataplane's claim validation because the internal gRPC channel
>   is protected by network policy (Phase 1) and mTLS (Phase 2).

---

### 3.4 App Revocation Proto (Internal — Controlplane gRPC Server, port 9000)

```protobuf
// app_revocation.proto
syntax = "proto3";
package dx.auth.v1;

option java_package = "org.cdpg.dx.auth.grpc";
option java_outer_classname = "AppRevocationProto";

service AppRevocationService {
    // Server-streaming: controlplane pushes revocation events to all connected dataplane pods.
    // One stream per pod. Reconnects with exponential backoff on disconnect.
    rpc WatchRevocations(WatchRevocationsRequest) returns (stream RevocationEvent);
}

message WatchRevocationsRequest {
    string service_id = 1;   // unique per pod: use K8s HOSTNAME env var
}

message RevocationEvent {
    string app_id   = 1;
    string reason   = 2;   // "REVOKED" | "EXPIRED" | "CONSTRAINT_CHANGED"
    int64 timestamp = 3;
}
```

---

## 4. Auth Flows — End to End

### 4.1 AppId Auth Flow (Pure gRPC)

```
External IoT device / script:
  gRPC SearchEntities(id="urn:dx:rs:domain/rs/group/item-1")
  metadata: { "x-app-id": "550e8400-e29b-41d4-a716-446655440000" }
       │
       ▼ [Dataplane gRPC Server, port 9090]
  GrpcAuthPipeline.authenticateAppId(appId, resourceId)
       │
       ├── CACHE HIT: Caffeine.get("550e8400...:urn:dx:rs:...")
       │     └── GrpcAuthResult (userId, queryTypes, attrs, accessPolicy, ...)
       │
       └── CACHE MISS:
             AppVerifyGrpcClient.verifyApp(appId, resourceId)
                  │ gRPC VerifyApp RPC → Controlplane port 9000
                  ▼
             [Controlplane: AppVerifyGrpcService]
               1. SELECT app_credentials WHERE app_id=? AND status='ACTIVE'
               2. CHECK expiry
               3. SELECT app_constraints WHERE app_id=? AND resource_id=?
               4. Fetch catalogue: queryTypes, accessPolicy for resource_id
               5. Return VerifyAppResponse {
                    user_id, issuer, roles: ["consumer"], policy_id,
                    access_policy, query_types, allowed_attributes,
                    access_entries, iid
                  }
                  │
                  ▼
             Dataplane: cache response (Caffeine, key="appId:resourceId", TTL=5min)
             GrpcAuthResult built from VerifyAppResponse
       │
       ▼
  GrpcAuthPipeline.checkRoles(authResult)
    → authResult.roles = ["consumer"] → passes (AppId always consumer)
       │
       ▼
  GrpcDataServiceImpl.searchEntities(request, authResult)
    → authResult.queryTypes: ["TEMPORAL", "ATTR", "GEO"]
    → authResult.allowedAttributes: ["temperature", "humidity"]
    → authResult.accessPolicy: "SECURE"
    → Elasticsearch query with filters applied
    → SearchEntitiesResponse
       │
       ▼
  GrpcAuditingHandler
    → audit event: authMethod=APP_KEY, userId=real-uuid, resourceId, endpoint
```

### 4.2 JWT Auth Flow — Fast Path (No Controlplane Call)

```
External Keycloak app (JWT with embedded policies):
  gRPC SearchEntities(id="urn:dx:rs:domain/rs/group/item-1")
  metadata: { "authorization": "Bearer eyJhbGciOiJSUzI1NiJ9..." }
       │
       ▼ [Dataplane gRPC Server, port 9090]
  GrpcAuthPipeline.authenticateJwt(token, resourceId)
    1. Extract "iss" claim (no verification yet)
    2. JwksResolver.resolve(issuer) → cached JwtAuth instance
    3. jwtAuth.authenticate(token) → validates RS256 sig + exp + aud → Vert.x User
    4. user.principal().containsKey("policies") == true → FAST PATH
    5. Extract: queryTypes, allowedAttributes, accessPolicy directly from principal
    → GrpcAuthResult (no controlplane call — 0 network hops)
       │
       ▼
  GrpcAuthPipeline.checkRoles(authResult)
    → roles from JWT principal → ["consumer"] or ["delegate"] → passes
       │
       ▼
  GrpcDataServiceImpl.searchEntities(request, authResult)
       │
       ▼
  GrpcAuditingHandler
```

### 4.3 JWT Auth Flow — Slow Path (Plain Keycloak Token → gRPC to Controlplane)

```
External Keycloak app (plain token, no embedded policies):
  gRPC SearchEntities(id="urn:dx:rs:domain/rs/group/item-1")
  metadata: { "authorization": "Bearer eyJhbGciOiJSUzI1NiJ9..." }
       │
       ▼ [Dataplane gRPC Server, port 9090]
  GrpcAuthPipeline.authenticateJwt(token, resourceId)
    1. Extract "iss" claim
    2. JwksResolver.resolve(issuer) → cached JwtAuth
    3. jwtAuth.authenticate(token) → validated User (sub, roles, iss in principal)
    4. user.principal().containsKey("policies") == false → SLOW PATH
    5. Extract from validated JWT principal:
         userId  = principal.getString("sub")
         roles   = principal.getJsonObject("realm_access").getJsonArray("roles")
         issuer  = principal.getString("iss")
    6. CatalogueAccessGrpcClient.checkItemAccess(userId, roles, issuer, resourceId)
              │ gRPC CheckItemAccess RPC → Controlplane port 9000
              ▼
         [Controlplane: CatalogueAccessGrpcService]
           1. Look up ACL: does userId have access to resourceId?
           2. Fetch catalogue: queryTypes, accessPolicy for resourceId
           3. Return CheckItemAccessResponse {
                iid, access_policy, resource_servers, policies, access_entries
              }
              │
              ▼
         Dataplane: build GrpcAuthResult from response + original validated User
    → GrpcAuthResult
       │
       ▼
  GrpcAuthPipeline.checkRoles(authResult)
       │
       ▼
  GrpcDataServiceImpl.searchEntities(request, authResult)
       │
       ▼
  GrpcAuditingHandler
```

---

## 5. GrpcAuthResult — Unified Auth State Carrier

```java
// GrpcAuthResult.java
// Used by both HTTP path and gRPC path. Eliminates dual AppAuthContext/User objects.
public record GrpcAuthResult(
    String userId,           // UUID — real user from DB or JWT "sub"
    String issuer,           // "dx-controlplane" (AppId) or Keycloak URL (JWT)
    List<String> roles,      // ["consumer"] or ["delegate"]
    String authMethod,       // "APP_KEY" | "JWT"
    String accessPolicy,     // "OPEN" | "SECURE" | "PII"
    List<String> queryTypes, // allowed query types for this resource
    List<String> allowedAttributes,  // attribute filter (empty = all)
    String policyId,         // UUID of the governing policy
    long expiryEpoch,        // 0 for AppId (no expiry per call — TTL handles it)
    String resourceId        // the resource being accessed
) {
    public boolean isAppKey() { return "APP_KEY".equals(authMethod); }
    public boolean isJwt()    { return "JWT".equals(authMethod); }

    // Build from VerifyAppResponse (AppId path)
    public static GrpcAuthResult fromVerifyApp(VerifyAppResponse resp, String resourceId) {
        return new GrpcAuthResult(
            resp.getUserId(), resp.getIssuer(), resp.getRolesList(), "APP_KEY",
            resp.getAccessPolicy(), resp.getQueryTypesList(),
            resp.getAllowedAttributesList(), resp.getPolicyId(),
            resp.getAccessEntriesList().stream()
                .mapToLong(AccessEntry::getExpiryEpoch).max().orElse(0L),
            resourceId);
    }

    // Build from CheckItemAccessResponse + validated JWT User (JWT slow path)
    public static GrpcAuthResult fromCheckItemAccess(
            CheckItemAccessResponse resp, io.vertx.ext.auth.User user, String resourceId) {
        JsonObject p = user.principal();
        List<String> roles = p.getJsonObject("realm_access").getJsonArray("roles")
            .stream().map(Object::toString).toList();
        // Extract queryTypes from NGSI-LD resource server entry
        List<String> queryTypes = resp.getResourceServersList().stream()
            .filter(rs -> "NGSI-LD".equalsIgnoreCase(rs.getName()))
            .findFirst()
            .map(rs -> rs.getQueryTypesList())
            .orElse(List.of());
        // Extract allowedAttributes from policies[0].cons
        List<String> allowedAttrs = resp.getPoliciesList().stream()
            .findFirst()
            .map(pol -> pol.getCons().getAllowedAttributesList())
            .orElse(List.of());
        return new GrpcAuthResult(
            p.getString("sub"), p.getString("iss"), roles, "JWT",
            resp.getAccessPolicy(), queryTypes, allowedAttrs,
            resp.getPolicyId(),
            resp.getAccessEntriesList().stream()
                .mapToLong(AccessEntry::getExpiryEpoch).max().orElse(0L),
            resourceId);
    }

    // Build from JWT fast path (policies embedded in token)
    public static GrpcAuthResult fromJwtFastPath(io.vertx.ext.auth.User user, String resourceId) {
        JsonObject p = user.principal();
        JsonObject policy = p.getJsonArray("policies").getJsonObject(0);
        JsonObject cons = policy.getJsonObject("cons");
        List<String> queryTypes = p.getJsonArray("resourceServer").stream()
            .map(JsonObject.class::cast)
            .filter(rs -> "NGSI-LD".equalsIgnoreCase(rs.getString("name")))
            .findFirst()
            .map(rs -> rs.getJsonArray("queryTypes").stream()
                .map(Object::toString).toList())
            .orElse(List.of());
        List<String> roles = p.getJsonObject("realm_access").getJsonArray("roles")
            .stream().map(Object::toString).toList();
        long expiry = cons.getJsonArray("access", new JsonArray()).stream()
            .map(JsonObject.class::cast)
            .mapToLong(a -> a.getLong("expiry", 0L)).max().orElse(0L);
        return new GrpcAuthResult(
            p.getString("sub"), p.getString("iss"), roles, "JWT",
            p.getString("accessPolicy"), queryTypes,
            cons.getJsonArray("allowedAttributes", new JsonArray())
                .stream().map(Object::toString).toList(),
            policy.getString("policyId"), expiry, resourceId);
    }
}
```

`GrpcAuthResult` is immutable and flows through the Future chain. No thread-locals,
no gRPC `Context.key()`, no `RoutingContext.put()` for the gRPC path.

---

## 6. GrpcAuthPipeline — Vert.x Native, Full Implementation

```java
// GrpcAuthPipeline.java
// Single class handling both AppId and JWT auth for gRPC callHandlers.
// The same class is also used from AppIdOrJwtAuthHandler for HTTP path.
public class GrpcAuthPipeline {

    private final AppVerifyGrpcClient appVerifyClient;
    private final CatalogueAccessGrpcClient catalogueAccessClient;
    private final JwksResolver jwksResolver;
    private final Cache<String, GrpcAuthResult> appVerifyCache; // key: appId:resourceId
    private final ConcurrentHashMap<String, Set<String>> appIdIndex; // for bulk invalidation

    /**
     * Main entry point. Call from inside request.handler(message -> { ... }).
     * resourceId extracted from the decoded proto message (e.g., SearchEntitiesRequest.getId()).
     */
    public Future<GrpcAuthResult> authenticate(MultiMap metadata, String resourceId) {
        String appId = metadata.get("x-app-id");
        if (appId != null && !appId.isBlank()) {
            return authenticateAppId(appId.trim(), resourceId);
        }

        String authHeader = metadata.get("authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authenticateJwt(authHeader.substring(7).trim(), resourceId, metadata);
        }

        return Future.failedFuture(new DxUnauthorizedException(
            "Missing credentials: provide x-app-id metadata or authorization: Bearer <jwt>"));
    }

    // ── AppId Path ────────────────────────────────────────────────────────────

    private Future<GrpcAuthResult> authenticateAppId(String appId, String resourceId) {
        String cacheKey = appId + ":" + resourceId;
        GrpcAuthResult cached = appVerifyCache.getIfPresent(cacheKey);
        if (cached != null) {
            // Still validate expiry even from cache
            if (isExpired(cached)) {
                appVerifyCache.invalidate(cacheKey);
                // fall through to fresh verify below
            } else {
                return Future.succeededFuture(cached);
            }
        }

        VerifyAppRequest req = VerifyAppRequest.newBuilder()
            .setAppId(appId)
            .setResourceId(resourceId)
            .setServiceId(serviceId())
            .build();

        return appVerifyClient.verifyApp(req)
            .map(resp -> {
                GrpcAuthResult result = GrpcAuthResult.fromVerifyApp(resp, resourceId);
                cacheAndIndex(appId, resourceId, cacheKey, result);
                return result;
            })
            .recover(err -> Future.failedFuture(mapGrpcError(err, "AppId verification failed")));
    }

    // ── JWT Path ──────────────────────────────────────────────────────────────

    private Future<GrpcAuthResult> authenticateJwt(String token, String resourceId,
                                                    MultiMap metadata) {
        String issuer;
        try {
            String[] parts = token.split("\\.");
            issuer = new JsonObject(new String(Base64.getUrlDecoder().decode(parts[1])))
                .getString("iss");
        } catch (Exception e) {
            return Future.failedFuture(new DxUnauthorizedException("Malformed JWT"));
        }

        return jwksResolver.resolve(issuer)
            .compose(jwtAuth -> jwtAuth.authenticate(new JsonObject().put("token", token)))
            .compose(user -> {
                JsonObject principal = user.principal();

                // Fast path — policies embedded directly in JWT
                if (principal.containsKey("policies")) {
                    return Future.succeededFuture(
                        GrpcAuthResult.fromJwtFastPath(user, resourceId));
                }

                // Slow path — call controlplane via gRPC for item access
                String userId = principal.getString("sub");
                List<String> roles = principal
                    .getJsonObject("realm_access", new JsonObject())
                    .getJsonArray("roles", new JsonArray())
                    .stream().map(Object::toString).toList();
                String iss = principal.getString("iss");

                // Extract delegation headers if present
                String delegatorId = metadata.get("did");
                boolean isDelegator = delegatorId != null && !delegatorId.isBlank();

                CheckItemAccessRequest req = CheckItemAccessRequest.newBuilder()
                    .setUserId(userId)
                    .addAllRoles(roles)
                    .setIssuer(iss)
                    .setResourceId(resourceId)
                    .setIsDelegator(isDelegator)
                    .setDelegatorId(delegatorId != null ? delegatorId : "")
                    .build();

                return catalogueAccessClient.checkItemAccess(req)
                    .map(resp -> GrpcAuthResult.fromCheckItemAccess(resp, user, resourceId))
                    .recover(err -> Future.failedFuture(
                        mapGrpcError(err, "Item access check failed")));
            });
    }

    // ── Role Check ────────────────────────────────────────────────────────────

    /** Equivalent of AuthorizationHandler.forRoles(CONSUMER, DELEGATE). */
    public Future<Void> checkRoles(GrpcAuthResult authResult) {
        if (authResult.isAppKey()) {
            return Future.succeededFuture();  // AppId is always consumer — skip
        }
        boolean permitted = authResult.roles().stream()
            .anyMatch(r -> "consumer".equals(r) || "delegate".equals(r));
        if (!permitted) {
            return Future.failedFuture(new DxForbiddenException(
                "Role not permitted: " + authResult.roles()));
        }
        return Future.succeededFuture();
    }

    // ── Cache Invalidation (called by AppRevocationStreamHandler) ─────────────

    public void invalidateAppId(String appId) {
        Set<String> keys = appIdIndex.remove(appId);
        if (keys != null) keys.forEach(appVerifyCache::invalidate);
        LOGGER.info("Invalidated {} cache entries for appId {}****",
            keys != null ? keys.size() : 0, appId.substring(0, 8));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void cacheAndIndex(String appId, String resourceId, String key, GrpcAuthResult r) {
        appVerifyCache.put(key, r);
        appIdIndex.computeIfAbsent(appId, k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    private boolean isExpired(GrpcAuthResult r) {
        return r.expiryEpoch() > 0 && System.currentTimeMillis() / 1000L > r.expiryEpoch();
    }

    private static String serviceId() {
        return "dx-dataplane-" + System.getenv().getOrDefault("HOSTNAME",
            UUID.randomUUID().toString().substring(0, 8));
    }

    private static Throwable mapGrpcError(Throwable err, String context) {
        String msg = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
        if (msg.contains("UNAUTHENTICATED")) return new DxUnauthorizedException(context + ": " + msg);
        if (msg.contains("PERMISSION_DENIED")) return new DxForbiddenException(context + ": " + msg);
        if (msg.contains("NOT_FOUND")) return new DxNotFoundException(context + ": " + msg);
        if (msg.contains("DEADLINE_EXCEEDED")) return new DxInternalServerErrorException(context + ": timeout");
        return new DxInternalServerErrorException(context + ": " + msg);
    }
}
```

---

## 7. GrpcServerVerticle — External gRPC Server (Port 9090)

```java
// GrpcServerVerticle.java
public class GrpcServerVerticle extends AbstractVerticle {

    private final GrpcAuthPipeline authPipeline;
    private final GrpcDataServiceImpl dataService;
    private final GrpcAuditingHandler auditingHandler;

    @Override
    public void start() {
        GrpcServer grpcServer = GrpcServer.server(vertx);

        // Register all data service methods
        grpcServer.callHandler(DataServiceGrpc.getSearchEntitiesMethod(),
            this::handleSearchEntities);
        grpcServer.callHandler(DataServiceGrpc.getGetLatestMethod(),
            this::handleGetLatest);
        grpcServer.callHandler(DataServiceGrpc.getStreamLatestMethod(),
            this::handleStreamLatest);

        int port = config().getInteger("grpcExternalPort", 9090);
        boolean tls = config().getBoolean("grpcExternalTls", true);

        HttpServerOptions options = new HttpServerOptions()
            .setUseAlpn(true)
            .setHttp2ClearTextUpgrade(false);

        if (tls) {
            options.setSsl(true)
                .setKeyCertOptions(new JksOptions()
                    .setPath(config().getString("keystore"))
                    .setPassword(config().getString("keystorePassword")));
        }

        vertx.createHttpServer(options)
            .requestHandler(grpcServer)
            .listen(port)
            .onSuccess(s -> LOGGER.info("External gRPC server started on port {}", port))
            .onFailure(e -> LOGGER.error("Failed to start gRPC server: {}", e.getMessage()));
    }

    private void handleSearchEntities(GrpcServerRequest<SearchEntitiesRequest, SearchEntitiesResponse> request) {
        request.handler(message -> {
            String resourceId = message.getId();

            authPipeline.authenticate(request.headers(), resourceId)
                .compose(auth -> authPipeline.checkRoles(auth).map(v -> auth))
                .compose(auth -> dataService.searchEntities(message, auth))
                .onSuccess(response -> request.response()
                    .status(GrpcStatus.OK)
                    .end(response))
                .onFailure(err -> {
                    auditingHandler.logFailure(request.headers(), resourceId, err);
                    request.response()
                        .status(GrpcErrorMapper.toStatus(err))
                        .end();
                });
        });

        request.exceptionHandler(err ->
            request.response().status(GrpcStatus.INTERNAL).end());
    }

    private void handleGetLatest(GrpcServerRequest<GetLatestRequest, GetLatestResponse> request) {
        request.handler(message -> {
            String resourceId = message.getId();

            authPipeline.authenticate(request.headers(), resourceId)
                .compose(auth -> authPipeline.checkRoles(auth).map(v -> auth))
                .compose(auth -> dataService.getLatest(message, auth))
                .onSuccess(response -> request.response()
                    .status(GrpcStatus.OK)
                    .end(response))
                .onFailure(err -> request.response()
                    .status(GrpcErrorMapper.toStatus(err))
                    .end());
        });
    }

    private void handleStreamLatest(GrpcServerRequest<StreamLatestRequest, LatestDataEvent> request) {
        request.handler(message -> {
            String resourceId = message.getId();

            authPipeline.authenticate(request.headers(), resourceId)
                .compose(auth -> authPipeline.checkRoles(auth).map(v -> auth))
                .onSuccess(auth -> dataService.streamLatest(message, auth, request.response()))
                .onFailure(err -> request.response()
                    .status(GrpcErrorMapper.toStatus(err))
                    .end());
        });
    }
}
```

---

## 8. Streaming — JWT Expiry and AppId Revocation

### 8.1 JWT StreamLatest — Per-Event Expiry Check

```java
// In GrpcDataServiceImpl.streamLatest():
public void streamLatest(StreamLatestRequest req, GrpcAuthResult auth,
                         GrpcServerResponse<LatestDataEvent> response) {
    long jwtExpEpoch = auth.expiryEpoch();  // from JWT "exp" claim (0 for AppId)

    dataEventBus.subscribe(auth.resourceId(), event -> {
        // JWT: check expiry on every event
        if (auth.isJwt() && jwtExpEpoch > 0
                && System.currentTimeMillis() / 1000L > jwtExpEpoch) {
            response.status(GrpcStatus.UNAUTHENTICATED).end();
            return;
        }
        response.write(LatestDataEvent.newBuilder()
            .setResourceId(auth.resourceId())
            .setPayload(ByteString.copyFromUtf8(event.getPayload()))
            .setObservedAtEpoch(event.getTimestamp())
            .build());
    });

    // AppId: register for revocation notification
    if (auth.isAppKey()) {
        String rawAppId = req.getId();  // need raw appId — store in GrpcAuthResult
        streamRevocationRegistry.register(auth.userId() + ":" + auth.resourceId(), () ->
            response.status(GrpcStatus.UNAUTHENTICATED).end());
    }
}
```

### 8.2 AppId Revocation via WatchRevocations

```java
// AppRevocationStreamHandler.java — runs at verticle startup
public class AppRevocationStreamHandler {

    private final GrpcClient grpcClient;
    private final GrpcAuthPipeline authPipeline;
    private final StreamRevocationRegistry streamRegistry;
    private final SocketAddress controlplaneAddr;

    public void start() {
        doSubscribe(1); // start with 1s initial delay for first connect
    }

    private void doSubscribe(long delaySeconds) {
        WatchRevocationsRequest req = WatchRevocationsRequest.newBuilder()
            .setServiceId(serviceId())
            .build();

        grpcClient.request(controlplaneAddr, AppRevocationServiceGrpc.getWatchRevocationsMethod())
            .compose(r -> { r.end(req); return r.response(); })
            .onSuccess(resp -> {
                LOGGER.info("WatchRevocations stream connected");
                resp.handler(event -> {
                    String appId = event.getAppId();
                    LOGGER.info("Revocation: appId={}**** reason={}", appId.substring(0, 8), event.getReason());
                    // 1. Evict from Caffeine cache
                    authPipeline.invalidateAppId(appId);
                    // 2. Close open StreamLatest streams for this appId
                    streamRegistry.invalidate(appId);
                });
                resp.endHandler(v -> scheduleReconnect(delaySeconds));
                resp.exceptionHandler(e -> scheduleReconnect(delaySeconds));
            })
            .onFailure(e -> scheduleReconnect(delaySeconds));
    }

    private void scheduleReconnect(long currentDelay) {
        long next = Math.min(currentDelay * 2, 60); // cap at 60s
        LOGGER.warn("WatchRevocations reconnecting in {}s", next);
        vertx.setTimer(TimeUnit.SECONDS.toMillis(next), t -> doSubscribe(next));
    }
}
```

---

## 9. HTTP Path — Same Internal gRPC Clients

The existing HTTP REST API (port 8443) continues working unchanged. The only change is that
`AppIdOrJwtAuthHandler` (security handler) now uses the **same internal gRPC clients**
instead of HTTP calls to controlplane.

```
HTTP Request (port 8443)
    │
    ▼
[AppIdOrJwtAuthHandler — security handler]
    │
    ├── X-App-Id present?
    │     AppVerifyGrpcClient.verifyApp(appId, resourceId)
    │       → same gRPC call to controlplane as gRPC path
    │       → same Caffeine cache
    │     ctx.setUser(User.create(authResultToPrincipal(result)))
    │     ctx.next()
    │
    └── Authorization: Bearer present?
          JwksResolver.validate(token) [local, no network]
          principal.containsKey("policies")?
            YES → fast path — ctx.setUser(jwtUser) → ctx.next()
            NO  → CatalogueAccessGrpcClient.checkItemAccess(userId, roles, ...)
                    → same gRPC call to controlplane as gRPC path
                  Enrich jwtUser principal with access result
                  ctx.setUser(enrichedUser) → ctx.next()
    │
    ▼
[AuthorizationHandler.forRoles(CONSUMER, DELEGATE)] — unchanged
    │
    ▼
[ItemAccessApplicableFilterHandlerNgsild] — unchanged
    │  AppId: ctx.user().principal() has "policies" from VerifyAppResponse → FAST PATH
    │  JWT fast: already has "policies" embedded
    │  JWT slow: principal enriched with access result from gRPC → FAST PATH
    ▼
[IdValidation → Business Logic → AuditingHandler] — all unchanged
```

### 9.1 Converting GrpcAuthResult to HTTP Principal

For the HTTP path, `GrpcAuthResult` must be converted to a Vert.x `User` principal so that
`ItemAccessApplicableFilterHandlerNgsild` (which reads `ctx.user().principal()`) works without
modification:

```java
// In AppIdOrJwtAuthHandler — convert gRPC result to principal-shaped JSON
private JsonObject authResultToPrincipal(GrpcAuthResult result) {
    // Build the SAME shape that ctx.user().principal() has after JWT auth
    // so that hasAccessPayload() fast path triggers in ItemAccessApplicableFilterHandlerNgsild
    return new JsonObject()
        .put("sub", result.userId())
        .put("iss", result.issuer())
        .put("iid", result.resourceId())
        .put("realm_access", new JsonObject()
            .put("roles", new JsonArray(result.roles())))
        .put("accessPolicy", result.accessPolicy())
        .put("policies", new JsonArray().add(new JsonObject()
            .put("policyId", result.policyId())
            .put("cons", new JsonObject()
                .put("allowedAttributes", new JsonArray(result.allowedAttributes()))
                .put("access", new JsonArray().add(new JsonObject()
                    .put("accessType", "api")
                    .put("expiry", result.expiryEpoch()))))))
        .put("resourceServer", buildResourceServer(result.queryTypes()));
}

private JsonArray buildResourceServer(List<String> queryTypes) {
    return new JsonArray().add(new JsonObject()
        .put("name", "NGSI-LD")
        .put("queryTypes", new JsonArray(queryTypes)));
}
```

This means `ItemAccessApplicableFilterHandlerNgsild` always takes the fast path — regardless
of whether the underlying verification was AppId (gRPC `VerifyApp`) or JWT slow-path (gRPC
`CheckItemAccess`). **Zero changes to `ItemAccessApplicableFilterHandlerNgsild`.**

---

## 10. Controlplane — New gRPC Services to Build

### 10.1 AppVerifyGrpcService

```java
// AppVerifyGrpcService.java (controlplane)
public class AppVerifyGrpcService extends AppVerifyServiceGrpc.AppVerifyServiceImplBase {

    private final AppCredentialRepository credentialRepo;
    private final AppConstraintRepository constraintRepo;
    private final CatalogueService catalogueService;

    @Override
    public void verifyApp(VerifyAppRequest request, StreamObserver<VerifyAppResponse> observer) {
        String appId = request.getAppId();
        String resourceId = request.getResourceId();

        credentialRepo.findByAppId(appId)
            .compose(cred -> {
                if (cred == null || !cred.isActive()) {
                    return Future.failedFuture(
                        Status.UNAUTHENTICATED.withDescription("AppId not found or inactive")
                            .asRuntimeException());
                }
                if (cred.isExpired()) {
                    return Future.failedFuture(
                        Status.UNAUTHENTICATED.withDescription("AppId expired")
                            .asRuntimeException());
                }
                return constraintRepo.findByAppIdAndResource(appId, resourceId);
            })
            .compose(constraint -> {
                if (constraint == null) {
                    return Future.failedFuture(
                        Status.PERMISSION_DENIED.withDescription(
                            "AppId not authorized for resource: " + resourceId)
                            .asRuntimeException());
                }
                return catalogueService.fetchAccessInfo(resourceId)
                    .map(catInfo -> buildResponse(constraint, catInfo));
            })
            .onSuccess(resp -> { observer.onNext(resp); observer.onCompleted(); })
            .onFailure(err -> observer.onError(toGrpcException(err)));
    }

    private VerifyAppResponse buildResponse(AppConstraint constraint, CatalogueInfo catInfo) {
        return VerifyAppResponse.newBuilder()
            .setUserId(constraint.getUserId())
            .setIssuer("dx-controlplane")
            .addAllRoles(List.of("consumer"))
            .setPolicyId(constraint.getPolicyId())
            .setAccessPolicy(constraint.getAccessPolicy())
            .addAllQueryTypes(catInfo.getQueryTypes())
            .addAllAllowedAttributes(constraint.getAllowedAttributes())
            .addAccessEntries(AccessEntry.newBuilder()
                .setAccessType("api")
                .setExpiryEpoch(constraint.getExpiryEpoch())
                .build())
            .setIid(constraint.getResourceId())
            .build();
    }
}
```

### 10.2 CatalogueAccessGrpcService

```java
// CatalogueAccessGrpcService.java (controlplane)
// gRPC equivalent of GET /iudx/v2/cat/item/access
// No JWT token received — dataplane sends validated claims only
public class CatalogueAccessGrpcService extends CatalogueAccessServiceGrpc.CatalogueAccessServiceImplBase {

    private final AclRepository aclRepo;
    private final CatalogueService catalogueService;

    @Override
    public void checkItemAccess(CheckItemAccessRequest request,
                                StreamObserver<CheckItemAccessResponse> observer) {
        String userId = request.getUserId();
        String resourceId = request.getResourceId();
        List<String> roles = request.getRolesList();

        // Look up user's ACL policy for this resource
        aclRepo.findPolicy(userId, resourceId, roles)
            .compose(policy -> {
                if (policy == null) {
                    return Future.failedFuture(
                        Status.PERMISSION_DENIED.withDescription(
                            "No access policy for user " + userId + " on resource " + resourceId)
                            .asRuntimeException());
                }
                return catalogueService.fetchAccessInfo(resourceId)
                    .map(catInfo -> buildResponse(policy, catInfo, resourceId));
            })
            .onSuccess(resp -> { observer.onNext(resp); observer.onCompleted(); })
            .onFailure(err -> observer.onError(toGrpcException(err)));
    }

    private CheckItemAccessResponse buildResponse(Policy policy, CatalogueInfo catInfo,
                                                  String resourceId) {
        ResourceServer rs = ResourceServer.newBuilder()
            .setName("NGSI-LD")
            .addAllQueryTypes(catInfo.getQueryTypes())
            .build();

        PolicyConstraints cons = PolicyConstraints.newBuilder()
            .addAllAllowedAttributes(policy.getAllowedAttributes())
            .addAccess(AccessEntry.newBuilder()
                .setAccessType("api")
                .setExpiryEpoch(policy.getExpiryEpoch())
                .build())
            .build();

        org.cdpg.dx.auth.grpc.Policy grpcPolicy = org.cdpg.dx.auth.grpc.Policy.newBuilder()
            .setPolicyId(policy.getPolicyId())
            .setCons(cons)
            .build();

        return CheckItemAccessResponse.newBuilder()
            .setIid(resourceId)
            .setAccessPolicy(catInfo.getAccessPolicy())
            .addResourceServers(rs)
            .addPolicies(grpcPolicy)
            .addAccessEntries(AccessEntry.newBuilder()
                .setAccessType("api")
                .setExpiryEpoch(policy.getExpiryEpoch())
                .build())
            .setPolicyId(policy.getPolicyId())
            .build();
    }
}
```

### 10.3 AppRevocationGrpcService (WatchRevocations)

```java
// AppRevocationGrpcService.java (controlplane)
public class AppRevocationGrpcService extends AppRevocationServiceGrpc.AppRevocationServiceImplBase {

    // All active streams: serviceId → StreamObserver
    private final ConcurrentHashMap<String, StreamObserver<RevocationEvent>> activeStreams =
        new ConcurrentHashMap<>();

    @Override
    public void watchRevocations(WatchRevocationsRequest request,
                                 StreamObserver<RevocationEvent> observer) {
        String serviceId = request.getServiceId();
        activeStreams.put(serviceId, observer);
        LOGGER.info("WatchRevocations subscriber connected: {}", serviceId);

        // Remove on disconnect
        ((ServerCallStreamObserver<RevocationEvent>) observer)
            .setOnCancelHandler(() -> {
                activeStreams.remove(serviceId);
                LOGGER.info("WatchRevocations subscriber disconnected: {}", serviceId);
            });
    }

    // Called from AppCredentialRepository / AppConstraintRepository on revocation or change
    public void publish(String appId, String reason) {
        RevocationEvent event = RevocationEvent.newBuilder()
            .setAppId(appId)
            .setReason(reason)
            .setTimestamp(System.currentTimeMillis() / 1000L)
            .build();

        // Push to all connected dataplane pods
        activeStreams.forEach((serviceId, observer) -> {
            try {
                observer.onNext(event);
            } catch (Exception e) {
                LOGGER.warn("Failed to push revocation to {}: {}", serviceId, e.getMessage());
                activeStreams.remove(serviceId);
            }
        });
    }
}
```

### 10.4 Revocation Triggers on Controlplane

```java
// AppCredentialRepository.java (controlplane) — trigger revocation on relevant changes
public Future<Void> revokeAppId(String appId, String adminUserId) {
    return db.update("UPDATE app_credentials SET status='REVOKED' WHERE app_id=?", appId)
        .compose(v -> {
            revocationService.publish(appId, "REVOKED");
            auditLog.log(adminUserId, "REVOKE_APPID", appId);
            return Future.succeededFuture();
        });
}

// AppConstraintRepository.java (controlplane) — fire CONSTRAINT_CHANGED on update
public Future<Void> updateConstraints(String appId, List<AppConstraint> newConstraints) {
    return db.runInTransaction(conn ->
        conn.update("DELETE FROM app_constraints WHERE app_id=?", appId)
            .compose(v -> conn.batchInsert(newConstraints))
            .compose(v -> {
                revocationService.publish(appId, "CONSTRAINT_CHANGED");
                return Future.succeededFuture();
            }));
}
```

---

## 11. Dataplane — Complete List of New/Modified Classes

### 11.1 New Classes

| Class | Package | Responsibility |
|---|---|---|
| `GrpcServerVerticle.java` | `grpc.server` | External gRPC server (port 9090); registers `callHandler` per method |
| `GrpcAuthPipeline.java` | `grpc.auth` | Full auth pipeline — AppId (gRPC `VerifyApp`) + JWT (JWKS + gRPC `CheckItemAccess`). Used by both gRPC callHandlers and HTTP security handler. |
| `GrpcAuthResult.java` | `grpc.auth` | Immutable record: userId, roles, authMethod, queryTypes, allowedAttributes, accessPolicy, policyId, expiryEpoch. Factory methods for each path. |
| `AppVerifyGrpcClient.java` | `grpc.client` | Vert.x gRPC client wrapping `AppVerifyService.VerifyApp`. Handles channel management, deadline, retry. |
| `CatalogueAccessGrpcClient.java` | `grpc.client` | Vert.x gRPC client wrapping `CatalogueAccessService.CheckItemAccess`. |
| `AppRevocationStreamHandler.java` | `grpc.client` | Subscribes to `AppRevocationService.WatchRevocations`; reconnects with backoff; calls `authPipeline.invalidateAppId()`. |
| `StreamRevocationRegistry.java` | `grpc.auth` | Tracks open `StreamLatest` gRPC response streams by appId; closes them on revocation event. |
| `GrpcDataServiceImpl.java` | `grpc.service` | Business logic for SearchEntities, GetLatest, StreamLatest. Accepts `GrpcAuthResult` for filters. Delegates to existing `SearchService` / `LatestService`. |
| `GrpcAuditingHandler.java` | `grpc.audit` | Publishes audit events to RabbitMQ via `DataBrokerService`. Same fields as HTTP auditing. |
| `GrpcErrorMapper.java` | `grpc.util` | Maps DX exceptions to Vert.x `GrpcStatus` codes. |
| `GrpcChannelManager.java` | `grpc.client` | Manages the single gRPC channel to controlplane (port 9000). Handles reconnection, TLS options. |

### 11.2 Modified Classes

| Class | Change |
|---|---|
| `AbstractApiServerVerticle` (dx-common) | Replace `MultiIssuerJwtAuthHandler` with `AppIdOrJwtAuthHandler` as `"authorization"` security handler. |
| `AppIdOrJwtAuthHandler.java` (new in dx-common or dataplane) | Uses `GrpcAuthPipeline` internally. Converts `GrpcAuthResult` to Vert.x `User` principal via `authResultToPrincipal()`. |
| `ApiServerVerticle.java` | Start `GrpcServerVerticle` + `AppRevocationStreamHandler` from `configureAdditionalRoutes()`. Inject shared `GrpcAuthPipeline`. |
| `config.json` | Add `grpcExternalPort` (9090), `grpcExternalTls` (bool), `controlplaneGrpcHost` + `controlplaneGrpcPort` (9000). |
| `pom.xml` | Add `vertx-grpc-server`, `vertx-grpc-client`, `protobuf-maven-plugin`. |

### 11.3 Classes NOT Changed

| Class | Reason |
|---|---|
| `ItemAccessApplicableFilterHandlerNgsild` | Fast path always triggered; sees normal `ctx.user().principal()` |
| `AuthorizationHandler.forRoles()` | Sees `realm_access.roles` from principal — same shape as JWT |
| `LatestController`, `NGSILDSearchController` | All `ctx.user().subject()`, `.principal().getString("iss")` work with real values from `GrpcAuthResult` converted principal |
| `AuditingHandler` | `ctx.user().subject()` returns real userId UUID |
| `SearchService`, `LatestService` | Business logic unchanged |

---

## 12. Internal gRPC Clients — AppVerifyGrpcClient and CatalogueAccessGrpcClient

```java
// AppVerifyGrpcClient.java
public class AppVerifyGrpcClient {

    private final GrpcClient grpcClient;
    private final SocketAddress controlplaneAddr;
    private final long deadlineMs;

    public Future<VerifyAppResponse> verifyApp(VerifyAppRequest request) {
        return grpcClient.request(controlplaneAddr, AppVerifyServiceGrpc.getVerifyAppMethod())
            .compose(grpcReq -> {
                grpcReq.deadline(Deadline.after(deadlineMs, TimeUnit.MILLISECONDS));
                grpcReq.end(request);
                return grpcReq.response();
            })
            .compose(resp -> {
                if (resp.status() != null && resp.status() != GrpcStatus.OK) {
                    return Future.failedFuture(new RuntimeException(
                        resp.status().name() + ": " + resp.statusMessage()));
                }
                return resp.last();  // unary — collect single response message
            });
    }
}
```

```java
// CatalogueAccessGrpcClient.java
public class CatalogueAccessGrpcClient {

    private final GrpcClient grpcClient;
    private final SocketAddress controlplaneAddr;
    private final long deadlineMs;

    public Future<CheckItemAccessResponse> checkItemAccess(CheckItemAccessRequest request) {
        return grpcClient.request(controlplaneAddr,
                CatalogueAccessServiceGrpc.getCheckItemAccessMethod())
            .compose(grpcReq -> {
                grpcReq.deadline(Deadline.after(deadlineMs, TimeUnit.MILLISECONDS));
                grpcReq.end(request);
                return grpcReq.response();
            })
            .compose(resp -> {
                if (resp.status() != null && resp.status() != GrpcStatus.OK) {
                    return Future.failedFuture(new RuntimeException(
                        resp.status().name() + ": " + resp.statusMessage()));
                }
                return resp.last();
            });
    }
}
```

Both clients share one `GrpcChannelManager` which maintains a single persistent HTTP/2
connection to `controlplaneGrpcHost:controlplaneGrpcPort`. All three RPCs (`VerifyApp`,
`CheckItemAccess`, `WatchRevocations`) multiplex over this one connection.

---

## 13. Caching Strategy

| Cache | Key | TTL | Size | Invalidation |
|---|---|---|---|---|
| AppId verify | `appId:resourceId` | 5 min | 5000 entries | `WatchRevocations` event → `invalidateAppId(appId)` removes all entries for that appId |
| JWT JWKS | per issuer URL | 1 hr (configurable) | per-issuer | Automatic; JwksResolver re-fetches on expiry |
| JWT slow path | none | — | — | Not cached — JWT has expiry; controlplane enforces ACL changes immediately |

**Why not cache JWT slow path?**
- JWT has its own expiry (`exp` claim). Caching the item access result could serve stale data
  after the JWT expires or after ACL changes.
- The slow path is called only for plain Keycloak tokens (no embedded policies). These are
  relatively rare — most tokens in production will have policies embedded.
- If JWT slow path caching becomes necessary for performance, a short TTL (30s) keyed on
  `userId:resourceId` could be added — but this requires cache invalidation on ACL changes
  (no current mechanism for that).

---

## 14. Error Mapping — DX Exceptions to gRPC Status

| Scenario | gRPC Status Code | Description |
|---|---|---|
| Missing credentials (no x-app-id or Bearer) | `UNAUTHENTICATED (16)` | Client must send credentials |
| Invalid / revoked AppId | `UNAUTHENTICATED (16)` | AppId not found or status != ACTIVE |
| JWT signature invalid / expired | `UNAUTHENTICATED (16)` | Token validation failed |
| JWT expired mid-stream | `UNAUTHENTICATED (16)` | Client must reconnect with fresh token |
| AppId revoked mid-stream | `UNAUTHENTICATED (16)` | Stream closed on revocation event |
| AppId not authorized for resource | `PERMISSION_DENIED (7)` | No constraint entry for appId+resourceId |
| JWT user has no ACL for resource | `PERMISSION_DENIED (7)` | CheckItemAccess returns PERMISSION_DENIED |
| Role not consumer/delegate | `PERMISSION_DENIED (7)` | `checkRoles()` fails |
| Resource does not exist | `NOT_FOUND (5)` | Catalogue lookup fails |
| Bad resource URN format | `INVALID_ARGUMENT (3)` | URN validation fails |
| gRPC to controlplane timeout | `DEADLINE_EXCEEDED (4)` | `verifyApp` or `checkItemAccess` > deadline |
| Rate limit exceeded | `RESOURCE_EXHAUSTED (8)` | Too many requests |
| Any unexpected error | `INTERNAL (13)` | Server-side bug |

```java
// GrpcErrorMapper.java
public class GrpcErrorMapper {
    public static GrpcStatus toStatus(Throwable err) {
        if (err instanceof DxUnauthorizedException)         return GrpcStatus.UNAUTHENTICATED;
        if (err instanceof DxForbiddenException)            return GrpcStatus.PERMISSION_DENIED;
        if (err instanceof DxNotFoundException)             return GrpcStatus.NOT_FOUND;
        if (err instanceof DxBadRequestException)           return GrpcStatus.INVALID_ARGUMENT;
        if (err instanceof DxTooManyRequestsException)      return GrpcStatus.RESOURCE_EXHAUSTED;
        if (err instanceof DxInternalServerErrorException)  return GrpcStatus.INTERNAL;
        // Propagated gRPC status from controlplane
        String msg = err.getMessage() != null ? err.getMessage() : "";
        if (msg.contains("UNAUTHENTICATED"))  return GrpcStatus.UNAUTHENTICATED;
        if (msg.contains("PERMISSION_DENIED")) return GrpcStatus.PERMISSION_DENIED;
        if (msg.contains("DEADLINE_EXCEEDED")) return GrpcStatus.DEADLINE_EXCEEDED;
        if (msg.contains("NOT_FOUND"))         return GrpcStatus.NOT_FOUND;
        return GrpcStatus.INTERNAL;
    }
}
```

---

## 15. Security Considerations

### 15.1 Transport Security

| Path | Requirement | Phase |
|---|---|---|
| External client → Dataplane gRPC (port 9090) | TLS required (gRPC over HTTP/2 with TLS) | Phase 1 |
| External client → Dataplane HTTP (port 8443) | TLS (already enforced) | Existing |
| Dataplane → Controlplane gRPC (port 9000) | Network policy (K8s) | Phase 1 |
| Dataplane → Controlplane gRPC (port 9000) | mTLS | Phase 2 |
| AppId in gRPC metadata | Encrypted by TLS — same protection as X-App-Id HTTP header | Phase 1 |

### 15.2 No Raw JWT Forwarding

`CheckItemAccessRequest` carries `userId`, `roles`, `issuer` — not the raw JWT token.
The controlplane trusts these claims because:
1. The dataplane is inside the network perimeter (K8s namespace, network policy)
2. Only the dataplane (trusted service) connects to port 9000
3. Phase 2: mTLS enforces this at the protocol level

### 15.3 AppId Logging

```
Log:   appId=550e8400****  (first 8 chars + ****)
Never: appId=550e8400-e29b-41d4-a716-446655440000  (full UUID)
```

### 15.4 gRPC Reflection — Disabled in Production

```java
// GrpcServerVerticle.java — DEV only
// grpcServer.addService(ProtoReflectionService.newInstance());
// Production: do NOT register ProtoReflectionService
```

### 15.5 Deadline Enforcement

All outgoing gRPC calls from dataplane must have a deadline:
```java
grpcReq.deadline(Deadline.after(config.getLong("grpcCallDeadlineMs", 3000), TimeUnit.MILLISECONDS));
```

All incoming gRPC calls on port 9090 must enforce a server-side deadline:
```java
// In GrpcServerVerticle callHandler — check if deadline set by client; enforce max
request.expirationHandler(() ->
    request.response().status(GrpcStatus.DEADLINE_EXCEEDED).end());
```

---

## 16. Comparison: HTTP Approach vs All-gRPC Approach

| Dimension | HTTP `/app/verify` approach (grpc-external-auth-design.md) | All-gRPC approach (this doc) |
|---|---|---|
| AppId verify protocol | HTTP GET `/app/verify` → JSON | gRPC `VerifyApp` RPC → Protobuf |
| JWT slow path protocol | HTTP GET `/cat/item/access` | gRPC `CheckItemAccess` RPC → Protobuf |
| Internal connection type | HTTP client (WebClient) + gRPC client (revocation) | gRPC client only (one channel for all) |
| Serialization (internal) | JSON (HTTP) | Protobuf (faster, smaller, type-safe) |
| Multiplexing | No (separate HTTP + gRPC connections) | Yes (one HTTP/2 channel for all internal calls) |
| Security model (internal) | HTTP TLS + gRPC TLS separately | gRPC mTLS for everything |
| Controlplane new endpoints | 1 HTTP endpoint (`/app/verify`) | 0 new HTTP endpoints; 2 new gRPC services |
| Dataplane new gRPC clients | 1 gRPC client (revocation only) | 3 gRPC clients (verify + access + revocation) |
| JWT token forwarding | No (extracted claims via HTTP) | No (extracted claims via gRPC) |
| Consistency | Mixed protocols internally | Single protocol internally |

**Recommendation:** The all-gRPC approach (this document) is architecturally cleaner and performs
better for internal communication. The cost is building two additional gRPC services on the
controlplane (`AppVerifyService` and `CatalogueAccessService`) instead of one HTTP endpoint.

---

## 17. Open Questions

| # | Question | Options | Impact |
|---|---|---|---|
| OQ1 | External gRPC port separate from internal? | 9090 external + 9000 internal (recommended) / single port with service-name routing | Security: separate ports simplifies network policy — 9000 never reachable from outside |
| OQ2 | TLS termination for external gRPC (port 9090)? | Dataplane terminates TLS / Ingress (Envoy/nginx) terminates → plain h2c internally | If ingress handles TLS: simpler dataplane code; if dataplane: more control, no trust in ingress |
| OQ3 | mTLS for internal gRPC (port 9000) in Phase 1 or Phase 2? | Phase 1 (stricter) / Phase 2 (network policy sufficient for Phase 1) | Phase 1 mTLS adds cert management complexity early |
| OQ4 | JWT slow path caching? | No cache (current) / Short-TTL cache by `userId:resourceId` | No cache: safe but extra gRPC call per request; cache: faster but stale on ACL change |
| OQ5 | Should AppId support `StreamLatest`? | Yes (with revocation close) / No (AppId for unary only) | Yes adds `StreamRevocationRegistry` complexity |
| OQ6 | `CheckItemAccess` for delegation — forward `did` from gRPC metadata? | Yes / No (AppId never, JWT maybe) | Delegate flows via gRPC need `did` in metadata and forwarded to `CheckItemAccessRequest` |
| OQ7 | Who builds the controlplane gRPC services? | Controlplane team / Coordinated | Timeline dependency |
| OQ8 | Should HTTP path also use gRPC clients to controlplane? | Yes (one protocol everywhere, same clients) / No (HTTP path keeps HTTP to controlplane) | Yes = consistent; No = less change to HTTP path in Phase 1 |

---

## 18. Phased Rollout

### Phase 1 — AppId (gRPC verify + HTTP path, 4–5 weeks)

**Controlplane:**
- `AppVerifyGrpcService.VerifyApp(appId, resourceId)` — queries app_credentials + app_constraints + catalogue
- `AppRevocationGrpcService.WatchRevocations()` — push stream
- `CONSTRAINT_CHANGED` event on constraint update
- K8s NetworkPolicy for port 9000 (dataplane namespace only)

**Dataplane:**
- `GrpcChannelManager` — single gRPC channel to controlplane port 9000
- `AppVerifyGrpcClient` — wraps `VerifyApp` RPC
- `AppRevocationStreamHandler` — subscribes to `WatchRevocations`; backoff reconnect
- `GrpcAuthPipeline` — AppId path (no JWT slow path yet)
- `GrpcAuthResult` record + factory methods
- `AppIdOrJwtAuthHandler` — security handler for HTTP path; uses `GrpcAuthPipeline`
- `GrpcServerVerticle` + `GrpcDataServiceImpl.searchEntities` + `getLatest` (no streaming yet)
- `GrpcErrorMapper`, `GrpcAuditingHandler`
- TLS on port 9090

### Phase 2 — JWT over gRPC (2–3 weeks)

**Controlplane:**
- `CatalogueAccessGrpcService.CheckItemAccess(userId, roles, resourceId)` — gRPC equiv of `/cat/item/access`

**Dataplane:**
- `CatalogueAccessGrpcClient` — wraps `CheckItemAccess` RPC
- `GrpcAuthPipeline.authenticateJwt()` — fast path + gRPC slow path
- JWT fast/slow path in `AppIdOrJwtAuthHandler` for HTTP requests (replaces HTTP `/cat/item/access` call)
- `GrpcAuthResult.fromJwtFastPath()` + `fromCheckItemAccess()` factory methods

### Phase 3 — Streaming + Hardening (2 weeks)

**Dataplane:**
- `GrpcDataServiceImpl.streamLatest()` — JWT per-event expiry + AppId revocation close
- `StreamRevocationRegistry`
- Deadline enforcement on incoming gRPC requests (port 9090)
- gRPC reflection disabled in production config
- Rate limiting in `GrpcAuthPipeline`

### Phase 4 — Security Hardening + Cleanup (1–2 weeks)

- mTLS on internal gRPC channel (port 9000)
- Delete dead code: `CheckItemAccessHandler`, `ResourcePolicyAuthorizationHandler`, `CatalogueVerticle`, `CatalogueServiceImpl`, `CatalogueClientImpl`
- Evaluate JWT slow path caching (if performance data shows it's needed)

---

## 19. Summary — What Is and Is Not gRPC

```
ALL gRPC:
  ✓ External client → Dataplane: gRPC DataService (SearchEntities, GetLatest, StreamLatest)
  ✓ Dataplane → Controlplane (AppId verify): gRPC AppVerifyService.VerifyApp
  ✓ Dataplane → Controlplane (JWT slow path): gRPC CatalogueAccessService.CheckItemAccess
  ✓ Controlplane → Dataplane (revocation): gRPC AppRevocationService.WatchRevocations
  ✓ HTTP path auth (AppId): same gRPC AppVerifyGrpcClient used internally
  ✓ HTTP path auth (JWT slow): same gRPC CatalogueAccessGrpcClient used internally

Still NOT gRPC (by design):
  ✗ JWT signature validation: local JWKS check — no network call at all
  ✗ JWT fast path (embedded policies): zero network calls — reads from token
  ✗ External HTTP REST API (port 8443): existing, unchanged, still HTTP

One gRPC channel (HTTP/2) from dataplane to controlplane carries:
  → VerifyApp calls (unary, on demand)
  → CheckItemAccess calls (unary, on demand)
  → WatchRevocations stream (persistent, reconnects on disconnect)
```
