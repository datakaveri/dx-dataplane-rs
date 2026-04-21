# gRPC External Client Auth Design — AppId + JWT over gRPC

**Version:** 5.0  
**Date:** 2026-04-07  
**Author:** Ankit Singh  
**Status:** Draft — Awaiting Manager Approval  
**Supersedes:** v4.0 (gRPC sections 4–7 rewritten: VerifyApp gRPC RPC removed; AppId verification now uses HTTP `/iudx/v2/app/verify`; grpc-java ServerInterceptor replaced by Vert.x-native GrpcAuthPipeline Future chain; AppAuthContext replaced by GrpcAccessResult record)

---

## 1. Architecture Correction — What the Code Actually Does

Before designing gRPC, we need a correct picture of the existing HTTP architecture. The earlier
design docs had this wrong.

### 1.1 The Real Two-Server Architecture

There are exactly **two servers** in the current system, not three:

```
┌────────────────────────────────────────┐    ┌──────────────────────────────────┐
│           CONTROLPLANE                 │    │           DATAPLANE              │
│  (Single authority: Auth + ACL + Cat)  │    │  (Resource / Data server)        │
│                                        │    │                                  │
│  GET /iudx/v2/cat/item/access          │    │  GET  /iudx/v2/latest/{id}       │
│    ← item access policy + filters      │    │  GET  /iudx/v2/entities          │
│    ← allowed query types               │    │  POST /iudx/v2/entities          │
│    ← allowed attributes                │    │  POST /ngsi-ld/v1/publish        │
│    ← expiry info for SECURE/PII        │    │                                  │
│                                        │    │  Data stored in Elasticsearch    │
│  gRPC AppRevocationService             │───►│  Calls controlplane for auth     │
│    ← WatchRevocations stream (push)   │    │  GET /app/verify (HTTP, cached)  │
└────────────────────────────────────────┘    └──────────────────────────────────┘
         ▲                                              ▲
         │ (Auth + ACL + Cat, all here)                 │ (Entry point for data queries)
         │                                              │
         └──────────────── External clients ────────────┘
                           call DATAPLANE, not controlplane
```

There is **no separate catalogue server** in the active code paths. The controlplane exposes
`/iudx/v2/cat/item/access` which handles catalogue metadata, ACL checks, and access policy in one
call.

### 1.2 Dead Code in the Dataplane (Do Not Reference in New Design)

The following classes exist in the codebase but are **not wired** in any active
`ControllerFactory` or handler chain. They are legacy and should eventually be deleted:

| Class | Why it is dead |
|---|---|
| `CheckItemAccessHandler` | Two-step old approach: `GET /cat/item` then `POST /acl/has_access`. Replaced by `ItemAccessApplicableFilterHandlerNgsild`. |
| `ResourcePolicyAuthorizationHandler` | Used `CatalogueService` (separate cat server). No controller uses it today. |
| `CatalogueVerticle` | Boots a Vert.x service proxy for `CatalogueServiceImpl`. Not deployed in `ApiServerVerticle`. |
| `CatalogueServiceImpl` / `CatalogueClientImpl` | Call a separate `catServerHost`. No active handler uses this path. |
| `AuthorizationServiceImpl` (rs.authorization) | Used by `ResourcePolicyAuthorizationHandler`. Dead because the handler is dead. |

### 1.3 The Real Handler Chain (What Active Code Does)

```
HTTP Request (from external client)
    │
    ▼
[MultiIssuerJwtAuthHandler]                  ← OpenAPI security handler (dx-common)
    │  Extracts Bearer token
    │  Reads "iss" claim from JWT
    │  Fetches JWKS from correct Keycloak issuer (JwksResolver, cached)
    │  Validates RS256 signature + exp + aud
    │  Sets ctx.user() with validated JWT claims
    ▼
[AuthorizationHandler.forRoles(CONSUMER, DELEGATE)]
    │  Checks Keycloak realm_access.roles
    │  Rejects non-consumer / non-delegate roles
    ▼
[ItemAccessApplicableFilterHandlerNgsild]    ← THE auth + filter gate
    │
    │  FAST PATH — JWT has "policies" field embedded:
    │    ctx.user().principal().containsKey("policies") == true
    │    → Read resourceServer, queryTypes, allowedAttributes, accessPolicy directly from JWT
    │    → No network call. ~0ms.
    │
    │  SLOW PATH — JWT does not have "policies" (plain Keycloak token):
    │    → GET /iudx/v2/cat/item/access?id={itemId}
    │        Authorization: Bearer {token}
    │        [optionally: isDelegator=true&did={delegatorId}]
    │    → Controlplane checks: catalogue entry for itemId + user's ACL
    │    → Returns: { result: [{ resourceServer, queryTypes, allowedAttributes,
    │                             accessPolicy, policies, access }] }
    │
    │  Either path sets into RoutingContext:
    │    - applicableFilters (queryTypes)
    │    - allowedAttributes
    │    - itemMetaData
    │    - iid (resource id)
    │    - accessPolicy (OPEN / SECURE / PII)
    │    - policyId
    ▼
[IdValidation]
    ▼
[Business logic handler — LatestService / NGSILDService / etc.]
    ▼
[AuditingHandler]
```

### 1.4 What `/iudx/v2/cat/item/access` Returns

This single endpoint on the controlplane is the complete auth + catalogue decision. The response
drives everything the dataplane needs:

```json
{
  "result": [{
    "id": "urn:dx:rs:domain/rs/group/item-1",
    "accessPolicy": "SECURE",
    "resourceServer": [
      {
        "name": "NGSI-LD",
        "queryTypes": ["TEMPORAL", "ATTR", "GEO"]
      }
    ],
    "allowedAttributes": ["temperature", "humidity"],
    "policies": [
      {
        "policyId": "policy-uuid-123",
        "cons": {
          "allowedAttributes": ["temperature", "humidity"],
          "access": [
            { "accessType": "api", "expiry": 1780000000 }
          ]
        }
      }
    ],
    "access": [
      { "accessType": "api", "expiry": 1780000000 }
    ]
  }]
}
```

For OPEN resources, it returns the same structure with `"accessPolicy": "open"` and no ACL check
is performed internally.

---

## 2. Why the Dataplane is the Entry Point (Not the Controlplane)

The user question: *"Shouldn't external clients call the controlplane since it has auth + cat + ACL?"*

The answer is about what the client **wants**:

```
Client wants DATA (sensor readings, NGSILD entities):
    External client ──► DATAPLANE  ← correct entry point
    (Dataplane holds Elasticsearch with the actual data)

Client wants AUTH (register AppId, approve access request, manage tokens):
    External client ──► CONTROLPLANE  ← correct entry point
    (Controlplane holds DB with users, policies, credentials)
```

The controlplane does NOT hold time-series sensor data — Elasticsearch does, and Elasticsearch
is managed by the dataplane. The controlplane is the **authority for auth decisions** but not the
**server for data queries**.

```
What would break if controlplane were the entry point:
  External client ──► Controlplane ──► Controlplane checks auth ──► Controlplane calls Dataplane for data?
  → Controlplane becomes a proxy — adds a network hop, adds complexity, violates separation of concerns
  → The existing codebase has ZERO proxy logic in the controlplane

Correct model:
  External client ──► Dataplane (gets data + consults controlplane for auth)
  External client ──► Controlplane (for registration, policy management, token ops)
```

---

## 3. Core Design Principle — AppId and JWT Are Mutually Exclusive

A request carries **either** `X-App-Id` **or** `Authorization: Bearer <jwt>` — never both.
This is not just a convention; the handler chain enforces it: whichever header is present first
wins, and the other path is never reached.

### 3.1 The Root Problem with Previous Approaches

Previous versions of this design tried to handle AppId by:
- Setting a fake/synthetic `ctx.user()` object
- Adding special-case checks in every handler (`isAppKeyRequest()`)
- Creating a separate `AppAuthContext` alongside the JWT `User` object

All of these break the clean separation and require modifying many downstream handlers.

### 3.2 The Right Solution — Controlplane Returns a Principal-Shaped Response

**Key insight:** The `ItemAccessApplicableFilterHandlerNgsild` fast path already reads everything
it needs from `ctx.user().principal()` — `resourceServer`, `queryTypes`, `policies`,
`allowedAttributes`, `accessPolicy`, `iid`. The fast path is triggered when:

```java
source.containsKey("policies")  // line 258 — this is the only trigger
```

If the controlplane returns a response for AppId verification that has **exactly these same
fields** (plus `sub`, `realm_access.roles`, `iss` that other handlers need), then the dataplane
can call `ctx.setUser(User.create(controlplaneResponse))` and every single downstream handler
works **without any modification**.

### 3.3 Two Separate Controlplane Endpoints — Old Flow Unchanged, New Flow Added

```
OLD FLOW (JWT) — completely unchanged:
  GET /iudx/v2/cat/item/access?id={resourceId}
  Authorization: Bearer <jwt>
  ← existing endpoint, existing response, no changes

NEW FLOW (AppId) — new endpoint, same response shape:
  GET /iudx/v2/app/verify?id={resourceId}
  X-App-Id: 550e8400-e29b-41d4-a716-446655440000
```

Both endpoints return **identical JSON structure**. The dataplane does not need to know which
one was called — it handles the response the same way in both cases.

**Response shape for `/iudx/v2/app/verify` (mirrors `/iudx/v2/cat/item/access`):**

```json
{
  "result": [{
    "sub": "user-uuid-who-owns-this-appid",
    "iss": "dx-controlplane",
    "iid": "urn:dx:rs:domain/rs/group/item-1",
    "realm_access": {
      "roles": ["consumer"]
    },
    "accessPolicy": "SECURE",
    "resourceServer": [
      {
        "name": "NGSI-LD",
        "queryTypes": ["TEMPORAL", "ATTR", "GEO"]
      }
    ],
    "policies": [
      {
        "policyId": "policy-uuid",
        "cons": {
          "allowedAttributes": ["temperature", "humidity"],
          "access": [{ "accessType": "api", "expiry": 1780000000 }]
        }
      }
    ],
    "access": [{ "accessType": "api", "expiry": 1780000000 }]
  }]
}
```

The `sub`, `iss`, and `realm_access` fields are **additions** specific to `/app/verify` — the
existing `/cat/item/access` does not need to return these because for JWT the user identity
already comes from the token itself. For AppId there is no token, so the controlplane must
provide the user identity.

Controlplane builds this from:
- `app_credentials` table → `userId` (for `sub`), status check
- `app_constraints` table → `policyId`, `allowedAttributes`, `expiry`, `access`
- Catalogue data → `resourceServer`, `queryTypes`, `accessPolicy` (same source as `/cat/item/access`)

### 3.4 Controlplane Implementation — `/iudx/v2/app/verify`

```java
// AppVerifyController.java (controlplane)
public void handle(RoutingContext ctx) {
    String appId = ctx.request().getHeader("X-App-Id");
    String resourceId = ctx.queryParams().get("id");

    if (appId == null || resourceId == null) {
        ctx.fail(new DxBadRequestException("X-App-Id header and id query param required"));
        return;
    }

    appCredentialRepository.findByAppId(appId)
        .compose(cred -> {
            if (cred == null || cred.getStatus() != AppStatus.ACTIVE) {
                return Future.failedFuture(new DxUnauthorizedException("Invalid or revoked AppId"));
            }
            if (cred.isExpired()) {
                return Future.failedFuture(new DxUnauthorizedException("AppId expired"));
            }
            return appConstraintRepository.findByAppIdAndResource(appId, resourceId);
        })
        .compose(constraint -> {
            if (constraint == null) {
                return Future.failedFuture(new DxForbiddenException(
                    "AppId not authorized for resource: " + resourceId));
            }
            // Fetch catalogue info for this resourceId (same source as /cat/item/access)
            return catalogueService.fetchCatalogueInfo(resourceId)
                .map(catInfo -> buildPrincipal(constraint, catInfo));
        })
        .onSuccess(principal -> ctx.response().setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(principal.encode()))
        .onFailure(ctx::fail);
}

private JsonObject buildPrincipal(AppConstraint constraint, JsonObject catInfo) {
    // Build response shaped exactly like ctx.user().principal() after JWT + /cat/item/access
    return new JsonObject()
        .put("sub", constraint.getUserId())           // real userId UUID
        .put("iss", "dx-controlplane")
        .put("iid", constraint.getResourceId())
        .put("realm_access", new JsonObject()
            .put("roles", new JsonArray().add("consumer")))
        .put("accessPolicy", constraint.getAccessPolicy())
        .put("resourceServer", catInfo.getJsonArray("resourceServer"))  // from catalogue
        .put("policies", new JsonArray().add(new JsonObject()
            .put("policyId", constraint.getPolicyId())
            .put("cons", new JsonObject()
                .put("allowedAttributes", new JsonArray(constraint.getAllowedAttributes()))
                .put("access", new JsonArray().add(new JsonObject()
                    .put("accessType", "api")
                    .put("expiry", constraint.getExpiryEpoch()))))))
        .put("access", new JsonArray().add(new JsonObject()
            .put("accessType", "api")
            .put("expiry", constraint.getExpiryEpoch())));
}
```

### 3.5 Updated HTTP Handler Chain — AppId Path

```
HTTP Request
    │
    ├─ X-App-Id present? ──────────────────────────────────────────────────────┐
    │                                                                           │
    │                                                                     [AppIdOrJwtAuthHandler]
    │                                                                       (security handler)
    │                                                                           │
    │                                                                     GET /iudx/v2/app/verify
    │                                                                       ?id={resourceId}
    │                                                                       X-App-Id: {uuid}
    │                                                                     (Caffeine cache, 5 min)
    │                                                                           │
    │                                                                     ctx.setUser(
    │                                                                       User.create(response))
    │                                                                           │
    └─ Authorization: Bearer present? ─────────────────────────────────────────┤
                                                                               │
                                                                         [AppIdOrJwtAuthHandler]
                                                                           validates JWT via JWKS
                                                                           ctx.setUser(jwtUser)
                                                                               │
                                                                               ▼
                                                              [AuthorizationHandler.forRoles(CONSUMER, DELEGATE)]
                                                                  reads realm_access.roles
                                                                  AppId: sees ["consumer"] ✓
                                                                  JWT:   sees Keycloak roles ✓
                                                                               │
                                                                               ▼
                                                              [ItemAccessApplicableFilterHandlerNgsild]
                                                                  hasAccessPayload() checks "policies"
                                                                  AppId: policies field present → FAST PATH ✓
                                                                  JWT (embedded): FAST PATH ✓
                                                                  JWT (plain):    slow path → /cat/item/access
                                                                               │
                                                                               ▼
                                                              [IdValidation]
                                                                               │
                                                                               ▼
                                                              [Business logic — LatestService / NGSILDService]
                                                                  ctx.user().subject()          → real userId ✓
                                                                  ctx.user().principal().iss    → "dx-controlplane" / Keycloak ✓
                                                                  ctx.user().principal().roles  → ["consumer"] ✓
                                                                               │
                                                                               ▼
                                                              [AuditingHandler]
                                                                  real userId, real role, real iss ✓
```

**Zero changes to `AuthorizationHandler`, `ItemAccessApplicableFilterHandlerNgsild`,
`LatestController`, `NGSILDSearchController`, or `AuditingHandler`.** They all see a normal
`ctx.user()` with real data — AppId or JWT, they cannot tell the difference and don't need to.

### 3.6 `AppIdOrJwtAuthHandler` — Single Security Handler for Both Auth Methods

```java
// AppIdOrJwtAuthHandler.java — replaces MultiIssuerJwtAuthHandler as security handler
// Registered as: routerBuilder.securityHandler("authorization", new AppIdOrJwtAuthHandler(...))
public class AppIdOrJwtAuthHandler implements AuthenticationHandler {

    private static final String APP_ID_HEADER = "X-App-Id";
    private final WebClient webClient;
    private final String appVerifyUrl;            // controlplane + "/iudx/v2/app/verify"
    private final Cache<String, JsonObject> appTokenCache;  // key: "appId:resourceId"
    private final JwksResolver jwksResolver;     // shared singleton

    @Override
    public void handle(RoutingContext ctx) {
        String appId = ctx.request().getHeader(APP_ID_HEADER);

        if (appId != null && !appId.isBlank()) {
            handleAppId(ctx, appId);
            return;
        }

        handleJwt(ctx);  // existing MultiIssuerJwtAuthHandler logic
    }

    private void handleAppId(RoutingContext ctx, String appId) {
        // resourceId may not be in path yet if getIdFromPathHandler hasn't run.
        // Extract it directly for the controlplane call.
        String resourceId = extractResourceId(ctx);

        String cacheKey = appId + ":" + resourceId;
        JsonObject cached = appTokenCache.getIfPresent(cacheKey);
        if (cached != null) {
            ctx.setUser(User.create(cached));
            ctx.next();
            return;
        }

        webClient.getAbs(appVerifyUrl)
            .putHeader("X-App-Id", appId)
            .addQueryParam("id", resourceId)
            .send()
            .onSuccess(resp -> {
                if (resp.statusCode() == 200) {
                    JsonObject principal = resp.bodyAsJsonObject();
                    appTokenCache.put(cacheKey, principal);
                    ctx.setUser(User.create(principal));
                    ctx.next();
                } else if (resp.statusCode() == 401) {
                    ctx.fail(new DxUnauthorizedException("Invalid or revoked AppId"));
                } else if (resp.statusCode() == 403) {
                    ctx.fail(new DxForbiddenException("AppId not authorized for this resource"));
                } else {
                    ctx.fail(new DxInternalServerErrorException("AppId verification failed"));
                }
            })
            .onFailure(err -> ctx.fail(new DxInternalServerErrorException(
                "Controlplane unreachable: " + err.getMessage())));
    }

    private void handleJwt(RoutingContext ctx) {
        String token = BearerTokenExtractor.extract(ctx);
        if (token == null || token.isBlank()) {
            ctx.fail(new DxUnauthorizedException(
                "Provide X-App-Id header or Authorization: Bearer <jwt>"));
            return;
        }
        String issuer;
        try {
            issuer = extractIssuer(token);
        } catch (Exception e) {
            ctx.fail(new DxUnauthorizedException("Malformed JWT"));
            return;
        }
        jwksResolver.resolve(issuer)
            .compose(jwtAuth -> jwtAuth.authenticate(new JsonObject().put("token", token)))
            .onSuccess(user -> { ctx.setUser(user); ctx.next(); })
            .onFailure(err -> ctx.fail(new DxUnauthorizedException(
                "Unauthorized: " + err.getMessage())));
    }
}
```

### 3.7 Cache Invalidation — AppId Revocation

The Caffeine cache is keyed by `"appId:resourceId"`. On revocation, all entries for a given
`appId` must be evicted. Caffeine does not natively support partial-key eviction, so use a
secondary index:

```java
// In AppIdOrJwtAuthHandler:
private final Cache<String, JsonObject> appTokenCache = Caffeine.newBuilder()
    .maximumSize(5000)
    .expireAfterWrite(Duration.ofMinutes(5))
    .build();

// Secondary index: appId → set of cacheKeys
private final ConcurrentHashMap<String, Set<String>> appIdIndex = new ConcurrentHashMap<>();

private void cacheToken(String appId, String resourceId, JsonObject principal) {
    String key = appId + ":" + resourceId;
    appTokenCache.put(key, principal);
    appIdIndex.computeIfAbsent(appId, k -> ConcurrentHashMap.newKeySet()).add(key);
}

public void invalidate(String appId) {
    Set<String> keys = appIdIndex.remove(appId);
    if (keys != null) keys.forEach(appTokenCache::invalidate);
    LOGGER.info("Invalidated {} cache entries for appId prefix={}****",
        keys != null ? keys.size() : 0, appId.substring(0, 8));
}
```

`invalidate(appId)` is called when the `WatchRevocations` gRPC stream delivers a revocation
event — same as before, only the cache structure changes.

### 3.8 Revocation via gRPC WatchRevocations (Still Required)

The `/iudx/v2/app/verify` endpoint is called per request (with caching). Revocation must be
propagated to the dataplane cache in real time. The `WatchRevocations` gRPC stream from
controlplane → dataplane is still the revocation channel:

```
Admin revokes appId
    │
    ▼
Controlplane: UPDATE app_credentials SET status=REVOKED
    │
    ▼
AppRevocationPublisher.publish(appId, "ADMIN_REVOKED")
    │ pushed over persistent gRPC stream
    ▼
Dataplane: AppIdOrJwtAuthHandler.invalidate(appId)
    → evicts all cache entries for this appId
    │
    ▼
Next request with this appId
    → cache miss → GET /iudx/v2/app/verify → controlplane returns 401 → 401 to client
```

> **Note:** The `WatchRevocations` gRPC stream is **only for revocation push** — it does NOT
> handle AppId verification anymore. Verification goes through the HTTP `/iudx/v2/app/verify`
> endpoint. This simplifies the gRPC proto (VerifyApp RPC can be removed entirely from the
> internal gRPC service — only WatchRevocations is needed).

### 3.9 Revised Internal gRPC Proto — Revocation Only

```protobuf
// app_revocation.proto — simplified: verification is now HTTP, gRPC only for revocation push
syntax = "proto3";
package dx.auth.v1;

service AppRevocationService {
    // Server-streaming: controlplane pushes revocation events to dataplane
    rpc WatchRevocations(WatchRevocationsRequest) returns (stream RevocationEvent);
}

message WatchRevocationsRequest {
    string service_id = 1;  // unique per pod: use HOSTNAME env var in K8s
}

message RevocationEvent {
    string app_id  = 1;
    string reason  = 2;  // "REVOKED" | "EXPIRED" | "CONSTRAINT_CHANGED"
    int64 timestamp = 3;
}
```

`VerifyApp` RPC is removed — it is no longer needed.

---

## 4. gRPC External Client Auth — Overview

With the corrected HTTP baseline above, gRPC is a parallel transport layer. External clients can
call the dataplane via gRPC instead of HTTP. Auth logic is the same; only the protocol differs.

The key insight: **the gRPC auth pipeline uses the exact same controlplane HTTP endpoints as the
HTTP auth path.** There is no separate gRPC-based `VerifyApp` RPC for verification — the
`/iudx/v2/app/verify` HTTP endpoint serves both HTTP and gRPC clients.

### 4.1 Port Strategy

```
External clients:
  Port 8443  → HTTPS REST          (existing, unchanged)
  Port 9090  → gRPC + TLS          (NEW — external client gRPC)

Internal (controlplane → dataplane):
  Port 9000  → gRPC internal        (revocation stream only — WatchRevocations)
               No VerifyApp RPC — verification goes via HTTP /app/verify
```

The external gRPC port (9090) and internal gRPC port (9000) are **strictly separate**:
- 9000 is network-policy restricted to controlplane→dataplane direction only
- 9090 is exposed to external clients (TLS required)

### 4.2 Auth Flows Over gRPC — Same Controlplane Calls as HTTP

```
┌─────────────────────────────────────────────────────────────────────────┐
│  DATAPLANE gRPC Server (port 9090)                                      │
│                                                                         │
│  Incoming gRPC call                                                     │
│    metadata: x-app-id or authorization: Bearer <jwt>                   │
│         │                                                               │
│         ▼                                                               │
│  GrpcAuthPipeline.authenticate(request, resourceId)                    │
│    ├── x-app-id present?                                                │
│    │      → HTTP GET /iudx/v2/app/verify?id={resourceId}               │
│    │           X-App-Id: {uuid}  → CONTROLPLANE                        │
│    │      → Response: principal-shaped JSON (same as JWT principal)    │
│    │      → User.create(response) — policies field present → fast path │
│    │                                                                    │
│    └── authorization: Bearer present?                                  │
│           → JwksResolver.validate(token) — local, no network           │
│           → principal.containsKey("policies")?                         │
│               YES → fast path (queryTypes, attrs, accessPolicy direct) │
│               NO  → HTTP GET /iudx/v2/cat/item/access?id={resourceId} │
│                          Authorization: Bearer {token} → CONTROLPLANE  │
│         │                                                               │
│         ▼                                                               │
│  GrpcAuthPipeline.checkRoles(user)                                     │
│    → realm_access.roles ∈ {consumer, delegate}                        │
│         │                                                               │
│         ▼                                                               │
│  GrpcDataServiceImpl                                                   │
│    searchEntities / getLatest / streamLatest                           │
│    → Elasticsearch query with auth-derived filters                     │
│         │                                                               │
│         ▼                                                               │
│  GrpcAuditingHandler                                                   │
│    → audit event via RabbitMQ (same DataBrokerService as HTTP path)    │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 5. gRPC + AppId — Full Detail

### 5.1 End-to-End Flow

```
External IoT device / script:
  gRPC call DataService.SearchEntities(id="urn:dx:rs:domain/rs/group/item-1")
  metadata: { "x-app-id": "550e8400-e29b-41d4-a716-446655440000" }
        │
        ▼  [DATAPLANE gRPC Server, port 9090]
  GrpcAuthPipeline.authenticate(request, resourceId)
    1. Extracts "x-app-id" from gRPC metadata
    2. Checks Caffeine cache: key = "550e8400...:urn:dx:rs:..."
          cache HIT  → User.create(cachedPrincipal) — skip network call
          cache MISS → HTTP GET /iudx/v2/app/verify?id=urn:dx:rs:...
                            X-App-Id: 550e8400-e29b-41d4-a716-446655440000
                            → CONTROLPLANE HTTP endpoint
                         Controlplane: SELECT app_credentials + app_constraints
                                       + catalogue lookup (same as /cat/item/access)
                                       → principal-shaped JSON with policies embedded
                       cache the principal, User.create(principal)
    3. User.create(response) — sets ctx-equivalent auth principal
    4. principal.containsKey("policies") == true → ALWAYS fast path
    5. Extract: queryTypes, allowedAttributes, accessPolicy from principal
        │
        ▼
  GrpcAuthPipeline.checkRoles(user)
    → user.principal().getJsonObject("realm_access").getJsonArray("roles")
    → ["consumer"] ← from /app/verify response → passes
        │
        ▼
  GrpcDataServiceImpl.searchEntities(request, accessResult)
    - accessResult has: queryTypes, allowedAttributes, accessPolicy, userId, iss
    - Applies attribute filter, temporal filter, geo filter
    - Queries Elasticsearch
    - Returns SearchEntitiesResponse
        │
        ▼
  GrpcAuditingHandler
    - Logs: authMethod=APP_KEY, sub=real-user-uuid, endpoint, duration
```

### 5.2 AppId Caffeine Cache — Keyed by appId:resourceId

Because `/iudx/v2/app/verify` requires both `appId` and `resourceId`, the cache entry is per
resource:

```java
// GrpcAuthPipeline.java (or shared with HTTP AppIdOrJwtAuthHandler)
private final Cache<String, JsonObject> appVerifyCache = Caffeine.newBuilder()
    .maximumSize(5000)
    .expireAfterWrite(Duration.ofMinutes(5))
    .build();

// Secondary index for revocation: appId → set of cacheKeys
private final ConcurrentHashMap<String, Set<String>> appIdIndex = new ConcurrentHashMap<>();

private void cacheAndIndex(String appId, String resourceId, JsonObject principal) {
    String key = appId + ":" + resourceId;
    appVerifyCache.put(key, principal);
    appIdIndex.computeIfAbsent(appId, k -> ConcurrentHashMap.newKeySet()).add(key);
}

public void invalidateAppId(String appId) {
    Set<String> keys = appIdIndex.remove(appId);
    if (keys != null) keys.forEach(appVerifyCache::invalidate);
    LOGGER.info("Invalidated {} cache entries for appId {}****",
        keys != null ? keys.size() : 0, appId.substring(0, 8));
}
```

`invalidateAppId(appId)` is called when `WatchRevocations` gRPC stream (port 9000) delivers a
revocation event. Same invalidation logic as the HTTP `AppIdOrJwtAuthHandler`.

### 5.3 GrpcAuthPipeline — AppId Path (Vert.x Native)

Vert.x gRPC server (`vertx-grpc-server`) does not have `ServerInterceptor`. Instead, auth runs
as a Future chain inside each `callHandler`:

```java
// GrpcAuthPipeline.java
public class GrpcAuthPipeline {

    private final WebClient webClient;
    private final String appVerifyUrl;          // controlplane + "/iudx/v2/app/verify"
    private final String catItemAccessUrl;      // controlplane + "/iudx/v2/cat/item/access"
    private final Cache<String, JsonObject> appVerifyCache;
    private final ConcurrentHashMap<String, Set<String>> appIdIndex;
    private final JwksResolver jwksResolver;

    /**
     * Full auth pipeline: credential verify + item access check.
     * Returns GrpcAccessResult carrying the authorized principal + access filters.
     *
     * @param headers    gRPC metadata (from GrpcServerRequest.headers())
     * @param resourceId resource URN extracted from the decoded request message
     */
    public Future<GrpcAccessResult> authenticate(MultiMap headers, String resourceId) {
        String appId = headers.get("x-app-id");

        if (appId != null && !appId.isBlank()) {
            return verifyAppId(appId, resourceId);
        }

        String authHeader = headers.get("authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            return verifyJwt(token, resourceId, headers);
        }

        return Future.failedFuture(new DxUnauthorizedException(
            "Provide x-app-id metadata or authorization: Bearer <jwt>"));
    }

    private Future<GrpcAccessResult> verifyAppId(String appId, String resourceId) {
        String cacheKey = appId + ":" + resourceId;
        JsonObject cached = appVerifyCache.getIfPresent(cacheKey);
        if (cached != null) {
            return Future.succeededFuture(extractAccessResult(User.create(cached), "APP_KEY"));
        }

        return webClient.getAbs(appVerifyUrl)
            .putHeader("X-App-Id", appId)
            .addQueryParam("id", resourceId)
            .send()
            .compose(resp -> {
                if (resp.statusCode() == 200) {
                    // Response is the principal-shaped JSON from /app/verify
                    // It ALWAYS has "policies" field → fast path guaranteed
                    JsonObject principal = resp.bodyAsJsonObject()
                        .getJsonArray("result").getJsonObject(0);
                    cacheAndIndex(appId, resourceId, principal);
                    return Future.succeededFuture(extractAccessResult(User.create(principal), "APP_KEY"));
                } else if (resp.statusCode() == 401) {
                    return Future.failedFuture(new DxUnauthorizedException("Invalid or revoked AppId"));
                } else if (resp.statusCode() == 403) {
                    return Future.failedFuture(new DxForbiddenException(
                        "AppId not authorized for resource: " + resourceId));
                }
                return Future.failedFuture(new DxInternalServerErrorException(
                    "AppId verification failed: HTTP " + resp.statusCode()));
            });
    }

    private Future<GrpcAccessResult> verifyJwt(String token, String resourceId, MultiMap headers) {
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
                // Fast path — policies embedded in JWT
                if (user.principal().containsKey("policies")) {
                    return Future.succeededFuture(extractAccessResult(user, "JWT"));
                }
                // Slow path — call controlplane /iudx/v2/cat/item/access
                return webClient.getAbs(catItemAccessUrl)
                    .putHeader("Authorization", "Bearer " + token)
                    .addQueryParam("id", resourceId)
                    .send()
                    .compose(resp -> {
                        if (resp.statusCode() == 200) {
                            JsonObject result = resp.bodyAsJsonObject()
                                .getJsonArray("result").getJsonObject(0);
                            return Future.succeededFuture(
                                buildAccessResultFromCatResponse(user, result, "JWT"));
                        }
                        return Future.failedFuture(new DxForbiddenException(
                            "Access denied: " + resp.bodyAsJsonObject().getString("detail", "forbidden")));
                    });
            });
    }

    /**
     * Fast path: principal already has "policies" embedded (always true for AppId;
     * true for JWT when token is policy-enriched).
     */
    private GrpcAccessResult extractAccessResult(User user, String authMethod) {
        JsonObject p = user.principal();
        // policies[0].cons.allowedAttributes
        JsonObject cons = p.getJsonArray("policies").getJsonObject(0)
            .getJsonObject("cons");
        JsonArray allowedAttrs = cons.getJsonArray("allowedAttributes", new JsonArray());
        // resourceServer[name=NGSI-LD].queryTypes
        JsonArray queryTypes = p.getJsonArray("resourceServer").stream()
            .map(JsonObject.class::cast)
            .filter(rs -> "NGSI-LD".equalsIgnoreCase(rs.getString("name")))
            .findFirst()
            .map(rs -> rs.getJsonArray("queryTypes"))
            .orElse(new JsonArray());

        return new GrpcAccessResult(user, authMethod, queryTypes, allowedAttrs,
            p.getString("accessPolicy"),
            p.getJsonArray("policies").getJsonObject(0).getString("policyId"));
    }

    /** Slow path: parse /iudx/v2/cat/item/access response (same logic as ItemAccessApplicableFilterHandlerNgsild) */
    private GrpcAccessResult buildAccessResultFromCatResponse(User user, JsonObject result, String authMethod) {
        JsonArray resourceServers = result.getJsonArray("resourceServer");
        JsonObject ngsiLdServer = resourceServers.stream()
            .map(JsonObject.class::cast)
            .filter(rs -> "NGSI-LD".equalsIgnoreCase(rs.getString("name")))
            .findFirst()
            .orElseThrow(() -> new DxBadRequestException("NGSI-LD resource server not in access response"));
        JsonArray queryTypes = ngsiLdServer.getJsonArray("queryTypes");

        JsonObject cons = Optional.ofNullable(result.getJsonArray("policies"))
            .filter(arr -> !arr.isEmpty())
            .map(arr -> arr.getJsonObject(0).getJsonObject("cons"))
            .orElse(null);
        JsonArray allowedAttrs = cons != null ?
            cons.getJsonArray("allowedAttributes", new JsonArray()) : new JsonArray();

        validateAccessType(result);  // expiry + accessType == "api" check — same as HTTP handler

        String policyId = Optional.ofNullable(result.getJsonArray("policies"))
            .filter(arr -> !arr.isEmpty())
            .map(arr -> arr.getJsonObject(0).getString("policyId"))
            .orElse(null);

        return new GrpcAccessResult(user, authMethod, queryTypes, allowedAttrs,
            result.getString("accessPolicy"), policyId);
    }
}
```

### 5.4 GrpcAccessResult — Carries Authorized Auth Context

```java
// GrpcAccessResult.java — replaces AppAuthContext + gRPC Context keys
public record GrpcAccessResult(
    User user,              // Vert.x User (from /app/verify or JWT auth)
    String authMethod,      // "APP_KEY" | "JWT"
    JsonArray queryTypes,   // allowed query types for this resource
    JsonArray allowedAttributes,  // attribute filter (empty = all)
    String accessPolicy,    // "OPEN" | "SECURE" | "PII"
    String policyId         // UUID of the policy (for audit)
) {
    public String userId()     { return user.subject(); }
    public String issuer()     { return user.principal().getString("iss"); }
    public boolean isAppKey()  { return "APP_KEY".equals(authMethod); }
    public boolean isJwt()     { return "JWT".equals(authMethod); }
}
```

`GrpcAccessResult` is passed directly to `GrpcDataServiceImpl` — no thread-local `Context.key()`
needed. This is idiomatic Vert.x: pass data through Future chains, not through side-channel
context objects.

### 5.5 callHandler Wiring — Vert.x Native

```java
// GrpcServerVerticle.java
GrpcServer grpcServer = GrpcServer.server(vertx);

grpcServer.callHandler(DataServiceGrpc.getSearchEntitiesMethod(), request -> {
    request.handler(searchRequest -> {
        String resourceId = searchRequest.getId();

        authPipeline.authenticate(request.headers(), resourceId)
            .compose(accessResult -> checkRoles(accessResult)
                .map(v -> accessResult))  // role check returns void, pass accessResult through
            .compose(accessResult ->
                dataServiceImpl.searchEntities(searchRequest, accessResult))
            .onSuccess(response -> request.response()
                .status(GrpcStatus.OK)
                .end(response))
            .onFailure(err -> request.response()
                .status(GrpcErrorMapper.toStatus(err))
                .end());
    });

    request.exceptionHandler(err ->
        request.response().status(GrpcStatus.INTERNAL).end());
});

// Role check — equivalent of AuthorizationHandler.forRoles(CONSUMER, DELEGATE)
private Future<Void> checkRoles(GrpcAccessResult accessResult) {
    if (accessResult.isAppKey()) {
        return Future.succeededFuture();  // AppId always consumer — skip role check
    }
    JsonArray roles = accessResult.user().principal()
        .getJsonObject("realm_access", new JsonObject())
        .getJsonArray("roles", new JsonArray());
    boolean authorized = roles.stream()
        .anyMatch(r -> "consumer".equals(r) || "delegate".equals(r));
    if (!authorized) {
        return Future.failedFuture(new DxForbiddenException("Role not permitted for data access"));
    }
    return Future.succeededFuture();
}
```

---

## 6. gRPC + JWT — Full Detail

### 6.1 End-to-End Flow

```
External Keycloak-authenticated app:
  gRPC call DataService.SearchEntities(id="urn:dx:rs:domain/rs/group/item-1")
  metadata: { "authorization": "Bearer eyJhbGciOiJSUzI1NiJ9..." }
        │
        ▼  [DATAPLANE gRPC Server, port 9090]
  GrpcAuthPipeline.verifyJwt(token, resourceId, headers)
    1. Decode JWT header: extract "iss" claim (no verification yet)
    2. JwksResolver.resolve(issuer) → cached JwtAuth (same resolver as HTTP path)
    3. jwtAuth.authenticate(token) → Vert.x User (RS256 signature + exp + aud checked)
    4. user.principal().containsKey("policies")?
         YES → Fast path:
               GrpcAuthPipeline.extractAccessResult(user, "JWT")
               queryTypes, allowedAttributes, accessPolicy read from principal directly
               → 0 network calls after JWKS (already cached in JwksResolver)
         NO  → Slow path:
               HTTP GET /iudx/v2/cat/item/access?id={resourceId}
               Authorization: Bearer {token}
               → Controlplane validates user's ACL for this resource
               → GrpcAuthPipeline.buildAccessResultFromCatResponse(user, response, "JWT")
        │
        ▼
  checkRoles(accessResult)
    → realm_access.roles ∈ {consumer, delegate} → passes
        │
        ▼
  GrpcDataServiceImpl.searchEntities(searchRequest, accessResult)
    - Elasticsearch query with queryTypes, allowedAttributes filters applied
    - Returns SearchEntitiesResponse
        │
        ▼
  GrpcAuditingHandler
    - Logs: authMethod=JWT, sub=userId, iss=Keycloak-issuer, endpoint, duration
```

### 6.2 JWT Fast Path vs Slow Path in gRPC — Identical to HTTP

The `GrpcAuthPipeline.verifyJwt()` fast/slow path logic is **identical in structure** to
`ItemAccessApplicableFilterHandlerNgsild.handle()`:

| | HTTP Handler | gRPC Auth Pipeline |
|---|---|---|
| Trigger | `source.containsKey("policies")` | `user.principal().containsKey("policies")` |
| Fast path reads | `ctx.user().principal()` | `user.principal()` (same object) |
| Slow path URL | `checkItemAndFilterUrl + "/iudx/v2/cat/item/access"` | `catItemAccessUrl` (same endpoint) |
| Slow path header | `Authorization: Bearer {rawToken}` | Same — token available in closure |
| Result | Sets `ctx` data for downstream handlers | Returns `GrpcAccessResult` |

The only difference is transport: HTTP path uses `RoutingContext`, gRPC path returns a `Future`.

### 6.3 Raw Token Availability for Slow Path

For the JWT slow path, the raw token string must be forwarded to the controlplane. In the gRPC
path, the token is available as a local variable in the `verifyJwt()` closure — no special
context key needed. It is passed directly to the `webClient.getAbs()` call:

```java
// In GrpcAuthPipeline.verifyJwt():
private Future<GrpcAccessResult> verifyJwt(String token, String resourceId, MultiMap headers) {
    // token is a local variable — available throughout the Future chain below
    return jwksResolver.resolve(issuer)
        .compose(jwtAuth -> jwtAuth.authenticate(...))
        .compose(user -> {
            if (user.principal().containsKey("policies")) { ... }
            // slow path — token is still in scope:
            return webClient.getAbs(catItemAccessUrl)
                .putHeader("Authorization", "Bearer " + token)  // ← in scope
                ...
        });
}
```

---

## 7. Auth Pipeline — Full gRPC Equivalent of HTTP Handler Chain

```
Incoming gRPC call (external, port 9090)
    │
    ▼  request.handler(message -> {
         resourceId = message.getId()
    │
    ▼
[GrpcAuthPipeline.authenticate(headers, resourceId)]
    │  x-app-id present?
    │    → cache hit? → User.create(cachedPrincipal)
    │    → cache miss → HTTP GET /iudx/v2/app/verify?id={resourceId}
    │                       X-App-Id: {uuid} → CONTROLPLANE
    │                   → User.create(principal)  (has "policies" → fast path guaranteed)
    │
    │  authorization: Bearer present?
    │    → JwksResolver.validate(token) [local, cached JWKS]
    │    → principal.containsKey("policies")?
    │        YES → fast path (queryTypes, attrs, accessPolicy direct from principal)
    │        NO  → HTTP GET /iudx/v2/cat/item/access?id={resourceId}
    │                   Authorization: Bearer {token} → CONTROLPLANE
    │
    │  neither? → Future.failedFuture(UNAUTHENTICATED)
    │
    ▼  → Future<GrpcAccessResult>
[checkRoles(accessResult)]                ← equivalent of AuthorizationHandler.forRoles
    │  AppId → skip (always consumer)
    │  JWT   → realm_access.roles ∈ {consumer, delegate}
    │        → other role → PERMISSION_DENIED
    ▼
[GrpcDataServiceImpl]                     ← business logic
    │  searchEntities(request, accessResult)
    │  getLatest(request, accessResult)
    │  streamLatest(request, accessResult)
    │  Reads: accessResult.queryTypes(), allowedAttributes(), accessPolicy()
    │  Delegates to: SearchService / LatestService (same as HTTP path)
    ▼
[GrpcAuditingHandler]                     ← equivalent of AuditingHandler
    │  Publishes audit event to RabbitMQ via DataBrokerService
    │  accessResult.authMethod(), accessResult.userId(), accessResult.issuer()
    │  → real identity in all cases (not synthetic)
    })
```

**No grpc-java `ServerInterceptor`, no `Context.key()`, no `Contexts.interceptCall()`.** All
auth state flows through `GrpcAccessResult` in the Future chain. This is idiomatic Vert.x.

---

## 8. Proto Definitions

### 8.1 Data Service Proto (New — External gRPC API)

```protobuf
// data_service.proto
syntax = "proto3";
package dx.data.v1;

option java_package = "org.cdpg.dx.data.grpc";
option java_outer_classname = "DataServiceProto";

service DataService {
    // NGSILD entity search — equivalent to POST/GET /iudx/v2/entities
    rpc SearchEntities(SearchEntitiesRequest) returns (SearchEntitiesResponse);

    // Latest data — equivalent to GET /iudx/v2/latest/{id}
    rpc GetLatest(GetLatestRequest) returns (GetLatestResponse);

    // Server-streaming — real-time push (new, no HTTP equivalent)
    rpc StreamLatest(StreamLatestRequest) returns (stream LatestDataEvent);
}

message SearchEntitiesRequest {
    string id        = 1;   // resource URN
    string timerel   = 2;   // after | before | between
    string time      = 3;   // ISO8601
    string end_time  = 4;   // ISO8601 (for "between")
    repeated string attrs = 5;
    int32 offset     = 6;
    int32 limit      = 7;
    string georel    = 8;
    string geometry  = 9;
    string coordinates = 10;
}

message SearchEntitiesResponse {
    repeated bytes entities = 1;   // JSON-encoded NGSILD entities (UTF-8)
    int32 total_hits        = 2;
    string next_page_token  = 3;
}

message GetLatestRequest {
    string id = 1;
    int32 size = 2;
    int32 page = 3;
}

message GetLatestResponse {
    repeated bytes entities = 1;
    int32 total_hits        = 2;
}

message StreamLatestRequest {
    string id          = 1;
    int64 since_epoch  = 2;
}

message LatestDataEvent {
    string resource_id          = 1;
    bytes payload               = 2;   // JSON-encoded entity
    int64 observed_at_epoch     = 3;
}
```

> **On `bytes` for entity payload:** NGSILD entities are schema-free JSON-LD. Defining full Protobuf
> types for them in Phase 1 is impractical — the entity schema is rich and varies per resource type.
> Using `bytes` (UTF-8 JSON) preserves JSON-LD flexibility while still getting Protobuf framing
> (HTTP/2, binary length-delimited framing, multiplexing). Full typed Protobuf entities can be
> added in a future phase.

### 8.2 AppRevocation Proto (Controlplane Internal — Revocation Only)

AppId **verification** is now done via HTTP `GET /iudx/v2/app/verify` — there is no `VerifyApp`
gRPC RPC. The internal gRPC service carries only the revocation push stream.

This is identical to what was already specified in Section 3.9:

```protobuf
// app_revocation.proto — revocation push only; no VerifyApp RPC
syntax = "proto3";
package dx.auth.v1;

option java_package = "org.cdpg.dx.auth.grpc";
option java_outer_classname = "AppRevocationProto";

service AppRevocationService {
    // Server-streaming: controlplane pushes revocation events to all connected dataplane pods
    rpc WatchRevocations(WatchRevocationsRequest) returns (stream RevocationEvent);
}

message WatchRevocationsRequest {
    string service_id = 1;  // unique per pod: use HOSTNAME env var in K8s (see GAP-8)
}

message RevocationEvent {
    string app_id   = 1;
    string reason   = 2;  // "REVOKED" | "EXPIRED" | "CONSTRAINT_CHANGED"
    int64 timestamp = 3;
}
```

The dataplane subscribes to this stream at startup. On each `RevocationEvent`, it calls
`grpcAuthPipeline.invalidateAppId(event.getAppId())` — same method used by the HTTP path's
`AppIdOrJwtAuthHandler`. Both HTTP and gRPC paths share the same Caffeine cache.

---

## 9. Dataplane — Complete List of Required Changes

### 9.1 New Classes (gRPC Server)

| Class | Package | Responsibility |
|---|---|---|
| `GrpcServerVerticle.java` | `grpc.server` | Boots Vert.x gRPC server on port 9090; registers `callHandler` per method using `GrpcAuthPipeline` |
| `GrpcAuthPipeline.java` | `grpc.auth` | **Core auth pipeline** — Future chain: `verifyAppId()` (HTTP `/app/verify`) or `verifyJwt()` (JWKS + optional `/cat/item/access`). Shared by all `callHandler`s. |
| `GrpcAccessResult.java` | `grpc.auth` | Record carrying: `user` (Vert.x), `authMethod`, `queryTypes`, `allowedAttributes`, `accessPolicy`, `policyId`. Replaces grpc-java `Context.Key` pattern. |
| `AppRevocationStreamHandler.java` | `grpc.auth` | Subscribes to `WatchRevocations` stream (port 9000); calls `grpcAuthPipeline.invalidateAppId()` on revocation event; reconnects with backoff (see GAP-4). |
| `StreamRevocationRegistry.java` | `grpc.auth` | Tracks open `StreamLatest` gRPC streams per AppId; closes affected streams on revocation. |
| `GrpcDataServiceImpl.java` | `grpc.service` | Implements `DataService` proto `callHandler` bodies; delegates to existing `SearchService` / `LatestService` using `GrpcAccessResult` filters. |
| `GrpcStreamLatestHandler.java` | `grpc.service` | Server-streaming for `StreamLatest`; JWT per-event expiry check + AppId revocation close (see Section 11). |
| `GrpcErrorMapper.java` | `grpc.util` | Maps DX exceptions → gRPC `GrpcStatus` codes (Vert.x `GrpcStatus`, not grpc-java `Status`). |
| `AccessResultParser.java` | `grpc.util` | **Shared utility** — extracts queryTypes, allowedAttributes, accessPolicy from `/cat/item/access` response JSON. Used by both `GrpcAuthPipeline` and `ItemAccessApplicableFilterHandlerNgsild` (refactor to eliminate duplication). |

### 9.2 Modified Classes (HTTP Path)

| Class | Change |
|---|---|
| `AbstractApiServerVerticle` (dx-common) | Replace `MultiIssuerJwtAuthHandler` with `AppIdOrJwtAuthHandler` as the `"authorization"` security handler. `AppIdOrJwtAuthHandler` calls `GET /iudx/v2/app/verify` for AppId, falls back to JWT via JWKS. |
| `ItemAccessApplicableFilterHandlerNgsild` | No change needed — `AppIdOrJwtAuthHandler` sets `ctx.user()` with a principal that has `"policies"` → fast path triggers automatically. |
| `ApiServerVerticle.java` | Start `GrpcServerVerticle` from `configureAdditionalRoutes()` when `grpcEnabled: true` (see GAP-10 for shared singleton wiring). |
| `config.json` | Add `grpcEnabled` (bool), `grpcExternalPort` (9090, default), `grpcExternalTls` (bool), `grpcRevocationPort` (9000), `controlPlaneDomain` (already exists — used for `/app/verify` URL). |
| `pom.xml` | Add `vertx-grpc-server` dependency; `protobuf-maven-plugin` for proto compilation. |

### 9.3 Classes to Eventually Delete (Legacy, Currently Dead)

| Class | Reason |
|---|---|
| `CheckItemAccessHandler` | Replaced by `ItemAccessApplicableFilterHandlerNgsild` |
| `ResourcePolicyAuthorizationHandler` | Uses dead `CatalogueService` path |
| `CatalogueVerticle` | Separate catalogue server no longer exists |
| `CatalogueServiceImpl` + `CatalogueClientImpl` | No active caller |
| `AuthorizationServiceImpl` (rs.authorization) | Depends on dead `CatalogueService` |

> **Note:** Do not delete these now — confirm with team that no path uses them (a production trace
> or grep of active controller registrations is the safe check). Once confirmed dead, delete in a
> cleanup PR.

---

## 10. Controlplane — Complete List of Required Changes

### 10.1 Existing Capability (Already Available — No Change Needed)

| Capability | Endpoint / Method | Status |
|---|---|---|
| JWT validation | Via Keycloak JWKS (dataplane validates locally) | No change |
| Item access policy + filters | `GET /iudx/v2/cat/item/access` | Already exists — unchanged |
| AppId HTTP verification | `GET /iudx/v2/app/verify` (returns principal-shaped JSON) | **New — must be built** |
| Revocation stream gRPC | `AppRevocationService.WatchRevocations()` | **New — must be built** |

### 10.2 Changes Required on Controlplane

#### 10.2.1 Build `/iudx/v2/app/verify` HTTP Endpoint

This is the single new HTTP endpoint on the controlplane. It does the same DB lookups as a
gRPC `VerifyApp` RPC would, but returns a principal-shaped JSON that the dataplane can use
directly as `ctx.user().principal()` — no special mapping needed.

```java
// AppVerifyController.java (controlplane) — new HTTP GET handler
public class AppVerifyController {

    private final AppCredentialRepository credentialRepo;
    private final AppConstraintRepository constraintRepo;
    private final CatalogueService catalogueService;  // same as /cat/item/access uses

    public void handle(RoutingContext ctx) {
        String appId = ctx.request().getHeader("X-App-Id");
        String resourceId = ctx.queryParams().get("id");

        if (appId == null || appId.isBlank() || resourceId == null || resourceId.isBlank()) {
            ctx.fail(new DxBadRequestException("X-App-Id header and id query param required"));
            return;
        }

        credentialRepo.findByAppId(appId)
            .compose(cred -> {
                if (cred == null || cred.getStatus() != AppStatus.ACTIVE) {
                    return Future.failedFuture(new DxUnauthorizedException("Invalid or revoked AppId"));
                }
                if (cred.isExpired()) {
                    return Future.failedFuture(new DxUnauthorizedException("AppId expired"));
                }
                return constraintRepo.findByAppIdAndResource(appId, resourceId)
                    .compose(constraint -> {
                        if (constraint == null) {
                            return Future.failedFuture(new DxForbiddenException(
                                "AppId not authorized for resource: " + resourceId));
                        }
                        // Fetch catalogue info (same source as /cat/item/access)
                        return catalogueService.fetchCatalogueInfo(resourceId)
                            .map(catInfo -> buildPrincipal(cred, constraint, catInfo));
                    });
            })
            .onSuccess(principal -> ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("result", new JsonArray().add(principal)).encode()))
            .onFailure(ctx::fail);
    }

    private JsonObject buildPrincipal(AppCredential cred, AppConstraint constraint, JsonObject catInfo) {
        // Response shaped exactly like ctx.user().principal() after JWT + /cat/item/access.
        // The "policies" field triggers the FAST PATH in ItemAccessApplicableFilterHandlerNgsild.
        return new JsonObject()
            .put("sub", cred.getUserId())              // real user UUID
            .put("iss", "dx-controlplane")
            .put("iid", constraint.getResourceId())
            .put("realm_access", new JsonObject()
                .put("roles", new JsonArray().add("consumer")))
            .put("accessPolicy", constraint.getAccessPolicy())
            .put("resourceServer", catInfo.getJsonArray("resourceServer"))
            .put("policies", new JsonArray().add(new JsonObject()
                .put("policyId", constraint.getPolicyId())
                .put("cons", new JsonObject()
                    .put("allowedAttributes", new JsonArray(constraint.getAllowedAttributes()))
                    .put("access", new JsonArray().add(new JsonObject()
                        .put("accessType", "api")
                        .put("expiry", constraint.getExpiryEpoch()))))))
            .put("access", new JsonArray().add(new JsonObject()
                .put("accessType", "api")
                .put("expiry", constraint.getExpiryEpoch())));
    }
}
```

OpenAPI spec registration (controlplane):
```yaml
/iudx/v2/app/verify:
  get:
    summary: Verify AppId and return principal-shaped access context
    parameters:
      - in: header
        name: X-App-Id
        required: true
        schema: { type: string, format: uuid }
      - in: query
        name: id
        required: true
        description: Resource URN to check authorization against
        schema: { type: string }
    responses:
      '200': { description: Principal-shaped access context for the AppId + resource }
      '401': { description: Invalid or revoked AppId }
      '403': { description: AppId not authorized for this resource }
```

#### 10.2.2 `CONSTRAINT_CHANGED` Revocation Event

When an appId's constraints are updated in the DB (admin changes which resources or what filters
the appId can access), the dataplane's cache becomes stale. Currently `RevocationEvent` only fires
on explicit revoke. Add `CONSTRAINT_CHANGED`:

```java
// AppConstraintRepository.java (controlplane) — fire eviction on constraint update
public Future<Void> updateConstraints(String appId, List<AppConstraint> newConstraints) {
    return db.runInTransaction(conn -> {
        return conn.update("DELETE FROM app_constraints WHERE app_id = ?", appId)
            .compose(v -> conn.batchInsert(newConstraints))
            .compose(v -> {
                revocationPublisher.publish(appId, "CONSTRAINT_CHANGED");
                return Future.succeededFuture();
            });
    });
}
```

Dataplane `AppRevocationStreamHandler` already calls `cachingClient.invalidate(appId)` on any
revocation event, so the `CONSTRAINT_CHANGED` reason is handled automatically — no dataplane
code change needed.

#### 10.2.3 Rate Limiting on `/iudx/v2/app/verify`

The Caffeine cache on the dataplane (5-min TTL) means `/app/verify` is not called on every
request — only on cache miss. However, on first access or after revocation, multiple concurrent
requests for the same appId can race to call the endpoint. Add per-appId rate limiting on the
controlplane:

```java
// In AppVerifyController.handle():
if (rateLimiter.isExceeded(appId)) {
    ctx.fail(new DxTooManyRequestsException("Too many verify requests for this AppId"));
    return;
}
```

#### 10.2.4 Secure the gRPC Internal Port (port 9000)

The internal gRPC port (9000) must only be reachable from the dataplane namespace.
**This is a Kubernetes/infrastructure change, not a code change:**

```yaml
# Kubernetes NetworkPolicy — allow only dataplane pods to reach controlplane port 9000
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: controlplane-grpc-internal
  namespace: dx-controlplane
spec:
  podSelector:
    matchLabels:
      app: controlplane
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: dx-dataplane
      ports:
        - port: 9000
          protocol: TCP
```

---

## 11. JWT over gRPC — Streaming Token Expiry Problem

This is a problem unique to gRPC server-streaming that does not exist in HTTP.

### 11.1 HTTP: No Problem

```
HTTP request 1: JWT exp=T+55min → valid → 200
  ... 55 minutes pass ...
HTTP request 2: JWT exp=T+55min → expired → 401
  → Client must refresh and re-authenticate
```

Each HTTP request carries its own JWT. Expiry is checked per-request. No state.

### 11.2 gRPC StreamLatest: Problem

```
StreamLatest(id="urn:dx:...") with JWT exp=T+55min
  → stream opens, data flows
  ...
  ... 55 minutes pass ...
  JWT expires
  → Stream is still open! Server has no way to know JWT expired unless it checks.
  → Security gap: data continues flowing to an identity whose token is no longer valid.
```

### 11.3 Recommended Solution: Per-Event Expiry Check

On every emitted event, check if the JWT expiry has passed. If yes, close the stream with
`UNAUTHENTICATED`:

```java
// GrpcServerVerticle.java — JWT StreamLatest callHandler (Vert.x native)
grpcServer.callHandler(DataServiceGrpc.getStreamLatestMethod(), request -> {
    request.handler(streamRequest -> {
        String resourceId = streamRequest.getId();

        authPipeline.authenticate(request.headers(), resourceId)
            .compose(accessResult -> checkRoles(accessResult).map(v -> accessResult))
            .onSuccess(accessResult -> {
                // JWT expiry epoch — available from validated principal
                long jwtExpEpoch = accessResult.user().principal().getLong("exp", Long.MAX_VALUE);

                dataEventBus.subscribe(resourceId, event -> {
                    // Per-event expiry check
                    if (System.currentTimeMillis() / 1000L > jwtExpEpoch) {
                        request.response()
                            .status(GrpcStatus.UNAUTHENTICATED)
                            .end();
                        return;
                    }
                    LatestDataEvent proto = LatestDataEvent.newBuilder()
                        .setResourceId(resourceId)
                        .setPayload(ByteString.copyFromUtf8(event.getPayload()))
                        .setObservedAtEpoch(event.getTimestamp())
                        .build();
                    request.response().write(proto);
                });
            })
            .onFailure(err -> request.response()
                .status(GrpcErrorMapper.toStatus(err))
                .end());
    });
});
```

### 11.4 AppId Streaming — Revocation Must Close the Stream

For AppId over `StreamLatest`, revocation from the controlplane must terminate the stream:

```java
// GrpcStreamLatestHandler.java (AppId path) — Vert.x native
grpcServer.callHandler(DataServiceGrpc.getStreamLatestMethod(), request -> {
    request.handler(streamRequest -> {
        String resourceId = streamRequest.getId();

        authPipeline.authenticate(request.headers(), resourceId)
            .compose(accessResult -> checkRoles(accessResult).map(v -> accessResult))
            .onSuccess(accessResult -> {
                // Extract appId from principal (set by /app/verify response)
                String appId = accessResult.user().principal().getString("appId", null);
                // Note: /app/verify response should include masked appId for logging,
                // but for revocation we need the original appId from the X-App-Id header
                String rawAppId = request.headers().get("x-app-id");

                // Register this stream for immediate revocation notification
                Runnable revocationCallback = () ->
                    request.response()
                        .status(GrpcStatus.UNAUTHENTICATED)
                        .end();

                streamRevocationRegistry.register(rawAppId, revocationCallback);

                // Subscribe to data events and stream to client
                dataEventBus.subscribe(resourceId, event -> {
                    LatestDataEvent proto = LatestDataEvent.newBuilder()
                        .setResourceId(resourceId)
                        .setPayload(ByteString.copyFromUtf8(event.getPayload()))
                        .setObservedAtEpoch(event.getTimestamp())
                        .build();
                    request.response().write(proto);
                });
            })
            .onFailure(err -> request.response()
                .status(GrpcErrorMapper.toStatus(err))
                .end());
    });
});
```

`StreamRevocationRegistry` subscribes to the same revocation events delivered by
`WatchRevocations` gRPC stream. When a `RevocationEvent` arrives for `appId`, all registered
stream callbacks are invoked to close their streams immediately.

---

## 12. Error Mapping — gRPC Status Codes

| DX Exception | gRPC Status | Scenario |
|---|---|---|
| `DxUnauthorizedException` | `UNAUTHENTICATED (16)` | Missing / invalid / revoked credential |
| JWT expired | `UNAUTHENTICATED (16)` | Token expiry (including mid-stream) |
| `DxForbiddenNoAccessException` | `PERMISSION_DENIED (7)` | Valid credential, no access to resource |
| `DxBadRequestException` | `INVALID_ARGUMENT (3)` | Bad resource ID, invalid time format |
| `DxNotFoundException` | `NOT_FOUND (5)` | Resource does not exist in catalogue |
| HTTP `/app/verify` timeout | `DEADLINE_EXCEEDED (4)` | Controlplane unreachable or slow (> configured deadline) |
| Rate limit exceeded | `RESOURCE_EXHAUSTED (8)` | Too many requests |
| Any other | `INTERNAL (13)` | Unexpected server error |

---

## 13. Security Considerations

### 13.1 Transport Requirements

| Path | Requirement |
|---|---|
| External client → Dataplane HTTP | TLS (already enforced, port 8443) |
| External client → Dataplane gRPC | TLS required (port 9090, gRPC over HTTP/2 with TLS) |
| Dataplane → Controlplane gRPC (internal) | Network policy (Phase 1), mTLS (Phase 2) |
| AppId in gRPC metadata | Encrypted by TLS — same protection as X-App-Id HTTP header |
| JWT in gRPC metadata | Encrypted by TLS — same as Authorization header |

### 13.2 gRPC Reflection — Must Be Disabled in Production

gRPC reflection allows clients to discover all RPCs and message schemas. In dev/staging this is
useful. In production it must be disabled:

```java
// Do NOT register ProtoReflectionService in production
// grpcServer.addService(ProtoReflectionService.newInstance());  // DEV only
```

### 13.3 AppId Logging (Same Rule as HTTP)

```
Log:   app:550e8400****   (first 8 chars + ****)
Never: app:550e8400-e29b-41d4-a716-446655440000  (full appId)
```

### 13.4 Deadline Enforcement

gRPC clients must set deadlines. If they do not, the server must enforce a maximum:

```java
// GrpcServerVerticle — set server-side deadline
grpcServer.callHandler(request -> {
    if (!request.deadline().isPresent()) {
        // Force a 30s deadline if client didn't set one
        request.expirationHandler(() ->
            request.response().status(GrpcStatus.DEADLINE_EXCEEDED).end());
    }
});
```

---

## 14. Comparison: HTTP vs gRPC Auth

| Dimension | HTTP + AppId | HTTP + JWT | gRPC + AppId | gRPC + JWT |
|---|---|---|---|---|
| Credential location | `X-App-Id` header | `Authorization` header | `x-app-id` gRPC metadata | `authorization` gRPC metadata |
| Auth enforcement | `AppIdOrJwtAuthHandler` (security handler) | Same `AppIdOrJwtAuthHandler` (falls back to JWT) | `GrpcAuthPipeline.verifyAppId()` | `GrpcAuthPipeline.verifyJwt()` |
| Credential verify | HTTP `GET /app/verify` + Caffeine cache | JWKS signature check (local) | Same HTTP `GET /app/verify` + same cache | Same JWKS check |
| Access/filter gate | `ItemAccessApplicableFilterHandlerNgsild` fast path (policies in principal) | `ItemAccessApplicableFilterHandlerNgsild` fast or slow path | `GrpcAuthPipeline` fast path (policies in result) | `GrpcAuthPipeline` fast or slow path |
| State carrier | `ctx.user()` (Vert.x `User`) | `ctx.user()` (Vert.x `User`) | `GrpcAccessResult` (in Future chain) | `GrpcAccessResult` (in Future chain) |
| Controlplane call | `GET /app/verify?id=` + cache | Embedded JWT (fast) or `GET /cat/item/access` (slow) | Same `GET /app/verify` + same cache | Same fast/slow as HTTP |
| Streaming | N/A | N/A | `StreamLatest` + revocation close | `StreamLatest` + per-event expiry check |
| Key shared components | `AppIdOrJwtAuthHandler` (cache + WebClient) | `JwksResolver` | `GrpcAuthPipeline` (same cache + WebClient) | `JwksResolver` (same instance) |

---

## 15. Open Questions

| # | Question | Options | Impact |
|---|---|---|---|
| OQ1 | gRPC server implementation — **RESOLVED**: Vert.x native (`vertx-grpc-server`) | `GrpcServer.callHandler` + `GrpcAuthPipeline` Future chain. No grpc-java `ServerInterceptor`. | Decision made: consistent with existing Vert.x codebase; no blocking thread bridges needed |
| OQ2 | External gRPC port — same as internal (9000) or separate (9090)? | Same port, route by service name / Separate ports | Security: separate ports preferred — 9000 carries only `WatchRevocations`, external clients must not reach it |
| OQ3 | TLS termination for external gRPC — at dataplane or ingress? | Dataplane terminates TLS / Ingress (Envoy) terminates → dataplane sees plain h2c | If ingress handles TLS, simpler dataplane code but requires trusted internal network |
| OQ4 | Should AppId support `StreamLatest`? | Yes / No (AppId for unary calls only) | If yes: stream revocation callback registry needed; adds complexity |
| OQ5 | JWT over gRPC streaming: per-event expiry check only, or also periodic Keycloak introspection? | Expiry check only (fast, no network) / Introspection interval (catches early revoked JWTs) | Tradeoff: latency vs. security for revoked-but-not-expired JWTs |
| OQ6 | `AccessResultParser` refactor — extract shared logic from `ItemAccessApplicableFilterHandlerNgsild` now or later? | Extract now (clean, avoid duplication) / Later (focused scope) | If extracted now, both HTTP and gRPC paths share one tested utility |
| OQ7 | `AuthorizationHandler.forRoles` for gRPC — implement `GrpcRoleAuthorizationInterceptor` as new class or share code? | New class / Extract interface from existing handler | Minor — both approaches are straightforward |

---

## 16. Phased Rollout

### Phase 1 — AppId (HTTP + gRPC, 3–4 weeks)

**Controlplane:**
- Implement `AppVerifyController` (`GET /iudx/v2/app/verify`) — principal-shaped response
- Implement `AppRevocationService.WatchRevocations()` gRPC stream (port 9000)
- `CONSTRAINT_CHANGED` revocation event on constraint update
- Network policy for gRPC internal port (9000) — controlplane→dataplane only

**Dataplane:**
- `AppIdOrJwtAuthHandler` — replaces `MultiIssuerJwtAuthHandler` as OpenAPI security handler
  - Calls `GET /iudx/v2/app/verify` for AppId, falls back to JWKS for JWT
  - Caffeine cache (`appId:resourceId → principal`, 5-min TTL) + secondary index for revocation
- No change needed to `ItemAccessApplicableFilterHandlerNgsild` — fast path auto-triggers
- `AppRevocationStreamHandler` — subscribes to `WatchRevocations`, calls `invalidateAppId()`
- `GrpcServerVerticle` + `GrpcAuthPipeline` — AppId path calls same `/app/verify` endpoint
- `GrpcAccessResult` record — carries auth context through Future chain
- `GrpcDataServiceImpl` — `SearchEntities` + `GetLatest` callHandlers (no streaming yet)
- TLS on external gRPC port (9090)

### Phase 2 — JWT over gRPC (1–2 weeks)

**Dataplane:**
- `GrpcAuthPipeline.verifyJwt()` — JWT fast path (policies embedded) + slow path (`/cat/item/access`)
- JWT role check in `GrpcAuthPipeline.checkRoles()` (AppId path already skips this)
- `AccessResultParser` shared utility extracted from `ItemAccessApplicableFilterHandlerNgsild`
  (refactor to eliminate duplicated `/cat/item/access` response parsing)

### Phase 3 — Streaming + Hardening (1–2 weeks)

**Dataplane:**
- `GrpcStreamLatestHandler` — AppId streaming with revocation close
- `GrpcStreamLatestHandler` — JWT streaming with per-event expiry check
- `StreamRevocationRegistry`
- Deadline enforcement on gRPC server
- gRPC reflection disabled in production config
- Rate limiting on `GrpcAuthPipeline.verifyAppId()` (client-side) + controlplane `/app/verify` (server-side)

### Phase 4 — Cleanup (1 week)

- Delete `CheckItemAccessHandler`, `ResourcePolicyAuthorizationHandler`, `CatalogueVerticle`,
  `CatalogueServiceImpl`, `CatalogueClientImpl` (after confirming dead in production trace)
- mTLS on internal gRPC channel (dataplane → controlplane, port 9000)

---

## 17. Gap Analysis — Missing Pieces Discovered from Code Audit

This section documents gaps found by reading the actual source code of the dataplane and dx-common.
These must all be resolved before implementation starts.

---

### GAP-1 (CRITICAL) — Security Handler Ordering Breaks AppId on HTTP

**Problem:**
`MultiIssuerJwtAuthHandler` is registered as an OpenAPI security handler in
`AbstractApiServerVerticle`:

```java
routerBuilder.securityHandler("authorization", authHandler);
```

OpenAPI security handlers run **before** any per-route handler. When an AppId request arrives
with no `Authorization: Bearer` header, `MultiIssuerJwtAuthHandler` immediately returns 401:

```java
String token = BearerTokenExtractor.extract(ctx);
if (token == null || token.isBlank()) {
    ctx.fail(new DxUnauthorizedException("Missing Bearer token"));  // ← 401, AppId never runs
    return;
}
```

`AppKeyAuthHandler` (designed as a per-route handler) **never executes**.

**Fix — Replace with `AppIdOrJwtAuthHandler` as the security handler:**

```java
// AppIdOrJwtAuthHandler.java — registered as "authorization" security handler
// Full implementation in Section 3.6; abbreviated here for reference
public void handle(RoutingContext ctx) {
    String appId = ctx.request().getHeader("X-App-Id");

    if (appId != null && !appId.isBlank()) {
        String resourceId = extractResourceId(ctx);
        String cacheKey = appId + ":" + resourceId;
        JsonObject cached = appVerifyCache.getIfPresent(cacheKey);
        if (cached != null) {
            ctx.setUser(User.create(cached));
            ctx.next();
            return;
        }
        // HTTP call to controlplane — response is principal-shaped JSON
        webClient.getAbs(appVerifyUrl)
            .putHeader("X-App-Id", appId)
            .addQueryParam("id", resourceId)
            .send()
            .onSuccess(resp -> {
                if (resp.statusCode() == 200) {
                    JsonObject principal = resp.bodyAsJsonObject()
                        .getJsonArray("result").getJsonObject(0);
                    cacheAndIndex(appId, resourceId, principal);
                    ctx.setUser(User.create(principal));  // ← real data, not synthetic
                    ctx.next();
                } else {
                    ctx.fail(new DxUnauthorizedException("Invalid or revoked AppId"));
                }
            })
            .onFailure(err -> ctx.fail(new DxInternalServerErrorException(
                "Controlplane unreachable: " + err.getMessage())));
        return;
    }

    // JWT path — existing MultiIssuerJwtAuthHandler logic
    String token = BearerTokenExtractor.extract(ctx);
    if (token == null || token.isBlank()) {
        ctx.fail(new DxUnauthorizedException("Provide X-App-Id or Authorization: Bearer <jwt>"));
        return;
    }
    String issuer = extractIssuer(token);
    jwksResolver.resolve(issuer)
        .compose(jwtAuth -> jwtAuth.authenticate(new JsonObject().put("token", token)))
        .onSuccess(user -> { ctx.setUser(user); ctx.next(); })
        .onFailure(err -> ctx.fail(new DxUnauthorizedException("Unauthorized: " + err.getMessage())));
}
```

**Wiring change in `AbstractApiServerVerticle`:**
```java
// Before:
routerBuilder.securityHandler("authorization", authHandler);  // MultiIssuerJwtAuthHandler

// After:
routerBuilder.securityHandler("authorization",
    new AppIdOrJwtAuthHandler(cachingAppCredentialClient, jwksResolver));
```

**Impact:** This is the only structural change to `AbstractApiServerVerticle`/`dx-common`. The
`AppKeyAuthHandler` as a separate per-route handler is no longer needed — the combined handler
does both.

---

### GAP-2 (CRITICAL) — `ctx.user()` is null for AppId → NPE in 4+ places

**Problem:**
Multiple handlers and controllers unconditionally call `ctx.user()` after the security handler:

```java
// AuthorizationHandler.forRoles() — line 32:
User user = ctx.user();
if (user == null) ctx.fail(new DxUnauthorizedException("User not authenticated."));

// LatestController — line 176:
ctx.user().principal().getJsonObject("realm_access").getJsonArray("roles");

// LatestController — line 130:
ctx.user().principal().getString("iss");

// LatestController — line 111:
ctx.user().subject();
```

If a security handler runs but doesn't set `ctx.user()`, every one of these crashes.

**Fix — `AppIdOrJwtAuthHandler` calls `GET /iudx/v2/app/verify` and sets `ctx.user()` with the response:**

The controlplane `/iudx/v2/app/verify` response is shaped exactly like a JWT principal (see
Section 3.2 and 3.3). It includes `sub`, `iss`, `realm_access.roles`, and `policies`. So:

```java
// In AppIdOrJwtAuthHandler.handleAppId():
webClient.getAbs(appVerifyUrl)
    .putHeader("X-App-Id", appId)
    .addQueryParam("id", resourceId)
    .send()
    .onSuccess(resp -> {
        JsonObject principal = resp.bodyAsJsonObject()
            .getJsonArray("result").getJsonObject(0);
        // principal is already shaped correctly — no fake/synthetic construction needed
        cacheAndIndex(appId, resourceId, principal);
        ctx.setUser(User.create(principal));   // ← this is real data, not synthetic
        ctx.next();
    });
```

This satisfies all callers **with real data from the database**:
- `AuthorizationHandler.forRoles(CONSUMER, DELEGATE)` → `realm_access.roles = ["consumer"]` ✓
- `ctx.user().subject()` → real userId UUID (from `app_credentials.user_id` in controlplane DB) ✓
- `ctx.user().principal().getString("iss")` → `"dx-controlplane"` ✓
- `ctx.user().principal().containsKey("policies")` → true → `ItemAccessApplicableFilterHandlerNgsild` FAST PATH ✓

**This is not a "synthetic user hack"** — it is the controlplane providing the user's real identity
for an AppId credential, just via HTTP instead of embedded in a JWT.

**Audit consequence:** `DataplaneAuditHelper.createAuditingLogs()` calls `UUID.fromString(userId)`.
The `sub` field from `/app/verify` is the real user's UUID from `app_credentials.user_id` — valid UUID. No NPE.

---

### GAP-3 — gRPC slow path needs raw Bearer token for `/cat/item/access` call

**Problem (original):**
The old design used grpc-java `Context.Key` to pass state between interceptors. The raw JWT
string was not accessible from the validated `User` object, requiring a separate `Context.Key`.

**Resolution — No longer a gap with the new design:**

`GrpcAuthPipeline.verifyJwt()` is a single method that handles both JWKS validation and the
slow path `/cat/item/access` call. The raw token is a **local variable in the closure** — it
never needs to be stored in any context object:

```java
private Future<GrpcAccessResult> verifyJwt(String token, String resourceId, MultiMap headers) {
    // 'token' is in scope for the entire Future chain below
    return jwksResolver.resolve(issuer)
        .compose(jwtAuth -> jwtAuth.authenticate(...))
        .compose(user -> {
            if (user.principal().containsKey("policies")) {
                return Future.succeededFuture(extractAccessResult(user, "JWT"));
            }
            // slow path — 'token' is still in scope here:
            return webClient.getAbs(catItemAccessUrl)
                .putHeader("Authorization", "Bearer " + token)  // ← no context key needed
                .addQueryParam("id", resourceId)
                .send()
                ...
        });
}
```

No `RAW_TOKEN_KEY`, no `GrpcAuthContext`, no grpc-java `Context` at all. The Future closure
approach eliminates the entire class of "passing state between interceptors" problems.

---

### GAP-4 (IMPORTANT) — WatchRevocations stream has no reconnection logic

**Problem:**
The stream is subscribed once at startup. If the controlplane restarts or network partitions,
the stream breaks. The dataplane loses all revocation notifications silently. Revoked AppIds
continue being served from cache for up to the TTL (5 min).

**Fix — Exponential backoff reconnection:**

```java
private void doSubscribe(GrpcAuthPipeline authPipeline, long delaySeconds) {
    WatchRevocationsRequest req = WatchRevocationsRequest.newBuilder()
        .setServiceId("dx-dataplane-" + System.getenv().getOrDefault("HOSTNAME", instanceId))
        .build();

    grpcClient.request(controlplaneAddr, AppRevocationServiceGrpc.getWatchRevocationsMethod())
        .compose(r -> { r.end(req); return r.response(); })
        .onSuccess(resp -> {
            LOGGER.info("WatchRevocations stream active");
            resp.handler(evt -> authPipeline.invalidateAppId(evt.getAppId()));
            resp.endHandler(v -> scheduleReconnect(authPipeline, delaySeconds));
            resp.exceptionHandler(e -> scheduleReconnect(authPipeline, delaySeconds));
        })
        .onFailure(e -> scheduleReconnect(authPipeline, delaySeconds));
}

private void scheduleReconnect(GrpcAuthPipeline authPipeline, long delaySeconds) {
    long next = Math.min(delaySeconds * 2, 60);
    LOGGER.warn("WatchRevocations reconnecting in {}s", next);
    vertx.setTimer(TimeUnit.SECONDS.toMillis(next), t -> doSubscribe(authPipeline, next));
}
```

---

### GAP-5 (IMPORTANT) — `AuditLog` has no `authMethod` field

**Problem:**
`DataPlaneAuditLog` and `AuditLog` have no field to distinguish JWT vs AppId auth. Security
analytics cannot determine which auth method was used from audit records.

**Fix (dx-common change):**
1. Add `authMethod: String` field to `AuditLog` and `DataPlaneAuditLog`
2. Add `authMethod` parameter to `DataplaneAuditHelper.createAuditingLogs()`
3. In controllers: detect from `ctx.user().principal().getString("authMethod", "JWT")`

This is a small change but touches the audit model in dx-common.

---

### GAP-6 (IMPORTANT) — AppId delegation (`did` header) is undefined

**Problem:**
`ItemAccessApplicableFilterHandlerNgsild` reads the `did` header for delegate flows. For AppId
requests, delegation makes no sense — AppId is registered for a specific user, not a delegate
relationship. But the code never checks: if AppId + `did` header, what happens?

**Fix:**
`AppIdOrJwtAuthHandler` should explicitly ignore `did` for AppId requests:
```java
if (appId != null && !appId.isBlank()) {
    if (ctx.request().getHeader("did") != null) {
        LOGGER.warn("'did' header ignored for AppId requests — delegation not supported");
    }
    // ... proceed with AppId verification
}
```

State explicitly in design: **AppId does not support delegation.**

---

### GAP-7 — gRPC `ServerInterceptor` API does not exist in Vert.x gRPC — **RESOLVED**

**Original problem:**
Earlier design drafts used `ServerInterceptor`, `Contexts.interceptCall()`,
`ForwardingServerCallListener` — grpc-java APIs. Vert.x gRPC server (`vertx-grpc-server`)
does NOT have this model.

**Resolution:**
Sections 4–7 have been completely rewritten to use the Vert.x-native `GrpcAuthPipeline`
Future chain (see Section 5.3 and 7). The approach:

```java
// Each callHandler uses GrpcAuthPipeline — idiomatic Vert.x, no grpc-java
server.callHandler(DataServiceGrpc.getSearchEntitiesMethod(), request -> {
    request.handler(message -> {
        authPipeline.authenticate(request.headers(), message.getId())
            .compose(accessResult -> checkRoles(accessResult).map(v -> accessResult))
            .compose(accessResult -> dataServiceImpl.searchEntities(message, accessResult))
            .onSuccess(response -> request.response().status(GrpcStatus.OK).end(response))
            .onFailure(err -> request.response()
                .status(GrpcErrorMapper.toStatus(err)).end());
    });
});
```

No `ServerInterceptor`, no `Context.key()`, no `Contexts.interceptCall()`. Auth state flows
through `GrpcAccessResult` in the Future chain — same pattern as the HTTP `RoutingContext` flow
but with Futures instead of chained handlers. OQ1 is resolved: Vert.x native.

---

### GAP-8 (IMPORTANT) — Multiple dataplane pods need unique `service_id`

**Problem:**
With K8s horizontal scaling (e.g., 3 pods), each pod's `WatchRevocationsRequest.service_id`
must be unique for the controlplane to track active streams. If all pods send the same `service_id`,
the controlplane cannot distinguish them.

**Fix:**
```java
// Use K8s pod name from HOSTNAME env var (set automatically in K8s)
String instanceId = System.getenv().getOrDefault("HOSTNAME",
    "dx-dataplane-" + UUID.randomUUID().toString().substring(0, 8));
```

Controlplane `AppRevocationPublisher` removes a stream from `activeStreams` when it errors.
Each pod connecting and disconnecting must be handled correctly. No code change needed on
controlplane if streams are stored as `StreamObserver` objects (error = stream broken = remove).

---

### GAP-9 (MINOR) — AppId scope per API is not stated explicitly

**Decision needed:**

| API | AppId | Reason |
|---|---|---|
| `GET /iudx/v2/latest/{id}` | YES | Consumer read |
| `POST/GET /iudx/v2/entities` | YES | Consumer read |
| `GET /iudx/v2/download` | TBD | Large file — separate decision |
| `POST /ngsi-ld/v1/publish` | **NO** | Provider publish — JWT required |
| Admin/onboarding endpoints | **NO** | Admin role required |

For Publish API specifically: `NGSILDDataPublishController` must explicitly reject AppId requests:
```java
if (RsRoutingContextHelper.isAppKeyRequest(ctx)) {
    ctx.fail(new DxForbiddenException("AppId cannot be used for publish — use JWT"));
    return;
}
```

---

### GAP-10 (MINOR) — `GrpcServerVerticle` startup location

**Problem:**
The design mentions `GrpcServerVerticle` but does not say where it starts relative to the HTTP
server and how it accesses shared objects (`AppIdOrJwtAuthHandler` cache, `JwksResolver`).

**Fix:**
Start within `ApiServerVerticle.configureAdditionalRoutes()` so it shares the same verticle
context. The `AppIdOrJwtAuthHandler` (HTTP) and `GrpcAuthPipeline` (gRPC) share the same
Caffeine cache and `WebClient` instance — inject these as fields on `ApiServerVerticle`:

```java
// ApiServerVerticle.java
private AppIdOrJwtAuthHandler appIdOrJwtAuthHandler;  // shared between HTTP security handler + gRPC

@Override
protected List<ApiController> createControllers(Vertx vertx, JsonObject config, URNGenerator urnGenerator) {
    // Build shared handler with cache + WebClient (used by HTTP security handler)
    this.appIdOrJwtAuthHandler = new AppIdOrJwtAuthHandler(
        WebClient.create(vertx),
        config.getString("controlPlaneDomain") + "/iudx/v2/app/verify",
        config.getString("controlPlaneDomain") + "/iudx/v2/cat/item/access",
        jwksResolver);
    return ControllerFactory.createControllers(vertx, config, urnGenerator);
}

@Override
protected void configureAdditionalRoutes(Router router, JsonObject config) {
    // existing file upload routes...
    router.post("/ngsi-ld/v1/upload")...

    // Start gRPC server alongside HTTP — GrpcAuthPipeline shares the same cache
    if (config.getBoolean("grpcEnabled", false)) {
        GrpcExternalServer.start(
            vertx,
            config.getInteger("grpcExternalPort", 9090),
            config,
            appIdOrJwtAuthHandler.getSharedCache(),  // same Caffeine cache
            appIdOrJwtAuthHandler.getWebClient(),     // same WebClient
            jwksResolver);                            // same JwksResolver
    }
}
```

---

### Summary: Does This Design Support Both HTTP + gRPC Simultaneously?

**YES — both transports run in parallel on different ports:**

```
Port 8443  → HTTPS REST  (existing — AppIdOrJwtAuthHandler replaces MultiIssuerJwtAuthHandler as security handler)
Port 9090  → gRPC + TLS  (new — GrpcAuthPipeline Future chain replaces HTTP handler chain concept)
Port 9000  → internal gRPC (revocation only — WatchRevocations stream from controlplane)

Shared singletons (one instance, used by both HTTP and gRPC paths):
  AppIdOrJwtAuthHandler   → Caffeine cache (appId:resourceId → principal) + WebClient for /app/verify
  GrpcAuthPipeline        → Same Caffeine cache + same WebClient + same JwksResolver
  JwksResolver            → JWKS fetch + JWT validation (cached per issuer)
  SearchService           → Elasticsearch queries (HTTP controllers + GrpcDataServiceImpl)
  LatestService           → Latest data queries (same)
  DataBrokerService       → Auditing publish (same RabbitMQ channel)
```

The HTTP path and gRPC path use the **same business logic classes**. Only the transport layer
(HTTP handlers vs gRPC call handlers) and the auth layer (RoutingContext vs gRPC Context)
differ. Elasticsearch, RabbitMQ, and controlplane connections are shared.
