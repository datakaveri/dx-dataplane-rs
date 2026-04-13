# Design Document: Dual Authentication Support (JWT + AppId)

**Version:** 3.0 (Deep Implementation Guide)
**Date:** March 2026
**Scope:** dx-dataplane-rs + dx-controlplane

---

## Table of Contents

1. Problem Statement
2. Current Architecture Analysis
3. Proposed Approaches & Recommendation
4. Controlplane: Deep Implementation Details
5. Dataplane: Deep Implementation Details
6. Data Flow: Handler-by-Handler Walkthrough
7. Exact Code Changes Per File
8. Security Considerations
9. Edge Cases & Caching Strategy
10. AppId Capabilities
11. JWT vs AppId Comparison
12. Configuration
13. Implementation Order
14. Files Summary

---

## 1. Problem Statement

Currently, all dataplane APIs authenticate requests exclusively via **JWT Bearer tokens** (Keycloak-issued). We need to also support **AppId-based** authentication so that:

- Machine-to-machine integrations can use a simple app identifier instead of managing JWT lifecycles
- The dataplane can accept either authentication method on any protected endpoint
- Clients don't need to handle token refresh — they just send the appId every time
- AppId permissions can be updated in real-time without re-issuing tokens

---

## 2. Current Architecture Analysis

### 2.1 Current Handler Chain (JWT Only)

Every protected route in the dataplane follows this exact pattern (from `LatestController.java`, `DownloadController.java`, `NGSILDSearchController.java`):

```java
builder.operation(OPERATION_NAME)
    .handler(auditingHandler::handleApiAudit)              // 1. Audit logging
    .handler(getIdFromPathHandler)                         // 2. Extract resource ID from URL
    .handler(AuthorizationHandler.forRoles(...))           // 3. JWT extraction & role validation
    .handler(itemAccessApplicableFilterHandler)            // 4. Fetch access info from controlplane
    .handler(idValidation)                                 // 5. Validate resource ID matches token
    .handler(this::handleActualRequest);                   // 6. Business logic
```

### 2.2 JwtData Record (The Core Auth Data Structure)

**File:** `org.cdpg.dx.common.model.JwtData`

```java
public record JwtData(
    String accessToken,    // Raw JWT string
    String sub,            // Subject — user UUID
    String iss,            // Issuer (Keycloak)
    String aud,            // Audience
    Long exp,              // Expiration timestamp
    Long iat,              // Issued-at timestamp
    String iid,            // Item ID — format: "provider:resourceId"
    String role,           // "consumer", "provider", "delegate"
    JsonObject cons,       // Constraints: {allowedAttributes: [...], access: [...]}
    String drl,            // Delegated role (when role=delegate)
    String did,            // Delegator ID (when role=delegate)
    String expiry          // Expiry string
)
```

**Critical fields used by downstream handlers:**
- `iid` → split on ":" to get resource ID → used by `AuthorizationServiceImpl.idValidation()`
- `role` → "consumer"/"provider"/"delegate" → used by `AuthorizationHandler`, `ProviderValidationHandler`
- `sub` → user UUID → used by `ProviderValidationHandler` (for provider role)
- `did` → delegator UUID → used by `ProviderValidationHandler` (for delegate role)
- `drl` → delegated role → used by `ProviderValidationHandler`
- `cons` → constraints JsonObject → used by `ItemAccessApplicableFilterHandlerNgsild` to extract `allowedAttributes`

### 2.3 RoutingContext Data Storage

**File:** `RsRoutingContextHelper.java`

```java
public class RsRoutingContextHelper {
    private static final String JWT_DATA = "jwtData";

    public static void setJwtData(RoutingContext ctx, JwtData jwtData) {
        ctx.put(JWT_DATA, jwtData);
    }

    public static Optional<JwtData> getJwtData(RoutingContext ctx) {
        return Optional.ofNullable(ctx.get(JWT_DATA));
    }
}
```

Additionally, `RoutingContextHelper` (from dx-common) stores:
- `setItemMetaData(ctx, result)` — full item metadata from controlplane
- `setApplicableFilter(ctx, queryTypes)` — allowed filter types (temporal, attr, geo)
- `setAllowedAttributes(ctx, attrs)` — attribute whitelist
- `setIid(ctx, itemId)` — resource item ID
- `setAccessPolicy(ctx, policy)` — "OPEN", "SECURE", etc.
- `setPolicyId(ctx, policyId)` — policy identifier
- `setOwnerUserId(ctx, ownerId)` — resource owner

### 2.4 Critical Finding: No Service-to-Service Auth

The dataplane **forwards the user's JWT** to controlplane for every downstream call. There is no service identity:

```java
// ItemAccessApplicableFilterHandlerNgsild.java (line ~160)
getRequest.addQueryParam("id", itemId)
    .putHeader("Authorization", "Bearer " + bearerToken);  // <-- user's JWT

// CheckItemAccessHandler.java (line ~67)
getRequest.putHeader("Authorization", "Bearer " + bearerToken);  // <-- user's JWT
```

**In the appId flow, there is no user JWT.** These downstream calls would fail.

### 2.5 Existing Dual-Path Pattern in ItemAccessApplicableFilterHandlerNgsild

The handler **already has** a two-branch pattern:

```java
public void handle(RoutingContext context) {
    if (context.user().principal().containsKey("policies")) {
        // PATH 1: JWT has policies embedded — use directly
        // Extract resourceServer, queryTypes, allowedAttributes from principal
        // Store in RoutingContext
    } else {
        // PATH 2: No policies — call controlplane
        // GET /iudx/v2/cat/item/access?id={itemId}
        // Parse response, store in RoutingContext
    }
}
```

**Both paths converge to store the same RoutingContext data.** This is the exact pattern we'll extend for appId auth.

### 2.6 What an AppId Represents (Controlplane DB)

**`aaa.app_credentials` table:**
```sql
app_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
user_id UUID NOT NULL,
app_secret_hash TEXT NOT NULL,        -- SHA-512 hashed
expiry_at TIMESTAMP NOT NULL,
status VARCHAR(20) DEFAULT 'active',  -- CHECK: active|revoked|expired
role VARCHAR,                         -- user's highest role
created_at TIMESTAMP,
modified_at TIMESTAMP,
revoked_at TIMESTAMP
```

**`app_constraints` table:**
```sql
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
app_id UUID REFERENCES app_credentials(app_id) ON DELETE CASCADE,
scope VARCHAR NOT NULL,               -- "data_access", "asset_management", "*"
entity_type VARCHAR NOT NULL,         -- "adex:Apps", "adex:DataBank", "*"
entity_id VARCHAR NOT NULL,           -- resource UUID or "*"
user_id UUID NOT NULL,
created_at TIMESTAMP DEFAULT now()
```

**Scope-to-Role Mapping (from `AppTokenServiceImpl`):**

| Scope | Maps to Role(s) |
|-------|-----------------|
| `*` | All user roles + all scopes |
| `data_access` | consumer (requires item validation) |
| `asset_management` | provider |
| `user_management` | org_admin, consumer |
| `compute_management` | compute |
| `cos_admin_access` | COS admin + all sub-scopes |
| `org_admin_access` | org admin + sub-scopes |

---

## 3. Proposed Approaches & Recommendation

### 3.1 Approach A: Token Exchange (appId → JWT)

Dataplane sends appId to controlplane, gets a JWT, uses JWT for downstream.

| Pros | Cons |
|------|------|
| Minimal dataplane changes | JWT generation overhead |
| Downstream handlers get JWT to forward | Essentially duplicates /app/token minus secret |

### 3.2 Approach B: Rich Verification (RECOMMENDED)

Dataplane sends appId to controlplane. Controlplane returns **all authorization data** in one response. Dataplane uses this directly — no JWT, no further controlplane calls.

| Pros | Cons |
|------|------|
| Single controlplane call (vs 2-3 in JWT flow) | New rich endpoint needed in controlplane |
| No JWT overhead | Dataplane handles dual auth context |
| Solves "no token for downstream calls" problem | Needs controlplane reachable |
| Instant revocation, live constraint updates | |

### 3.3 Approach C: Client-Side Token Exchange

Client gets JWT from controlplane first, then calls dataplane. Zero dataplane changes but pushes complexity to clients and contradicts appId-only requirement.

**Recommendation: Approach B** — solves all architectural problems cleanly.

---

## 4. Controlplane: Deep Implementation Details

### 4.1 Trust Model for `/app/verify`

The endpoint is **internal-only** (not public-facing). Secured by network policy (K8s service mesh / private subnet). Optionally add `X-Service-Key` header validation.

### 4.2 New Endpoint: `POST /iudx/auth/v2/app/verify`

**Request:**
```json
POST /iudx/auth/v2/app/verify
Content-Type: application/json

{
  "appId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Success Response (200):**
```json
{
  "type": "urn:dx:controlPanel:success",
  "title": "Success",
  "result": {
    "appId": "550e8400-e29b-41d4-a716-446655440000",
    "userId": "user-uuid",
    "role": "consumer",
    "status": "active",
    "expiresAt": "2026-06-18T00:00:00Z",
    "constraints": [
      {
        "scope": "data_access",
        "entityType": "resource",
        "entityId": "b58da193-23d9-43eb-b98a-123abc"
      }
    ],
    "accessibleResources": [
      {
        "entityId": "b58da193-23d9-43eb-b98a-123abc",
        "accessPolicy": "SECURE",
        "applicableFilters": ["temporal", "attr", "geo"],
        "allowedAttributes": ["speed", "temperature", "location"],
        "resourceServerUrl": "rs.iudx.io",
        "ownerUserId": "owner-uuid",
        "type": "adex:Resource"
      }
    ]
  }
}
```

**Error Responses:** 404 (not found), 401 (expired), 403 (revoked/disabled)

### 4.3 AppVerifyServiceImpl — Validation Logic

Reuses existing patterns from `AppTokenServiceImpl.validateApp()`:

```java
public class AppVerifyServiceImpl implements AppVerifyService {

    private final AppCredentialsDAO appCredentialsDAO;
    private final AppConstraintsDAO appConstraintsDAO;
    private final ItemService itemService;
    private final KeycloakUserService keycloakUserService;

    @Override
    public Future<JsonObject> verify(String appIdStr) {
        UUID appId = UUID.fromString(appIdStr);

        return appCredentialsDAO.getById(appId)
            .compose(app -> {
                // ── STEP 1: Validate app status ──
                // Reuse same checks from AppTokenServiceImpl.validateApp()
                // but WITHOUT the secret hash comparison

                if (app == null) {
                    return Future.failedFuture(new DxNotFoundException("App not found"));
                }
                if (app.revokedAt() != null) {
                    return Future.failedFuture(new DxForbiddenException("App revoked"));
                }
                if (!"active".equals(app.status())) {
                    return Future.failedFuture(new DxForbiddenException("App not active"));
                }
                LocalDateTime expiry = LocalDateTime.parse(app.expiryAt());
                if (expiry.isBefore(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))) {
                    return Future.failedFuture(new DxUnauthorizedException("App expired"));
                }

                // ── STEP 2: Fetch constraints ──
                return appConstraintsDAO.getAllWithFilters(
                    new FilterBuilder().eq("app_id", appId).build()
                );
            })
            .compose(constraints -> {
                // ── STEP 3: Fetch resource details for each constraint ──
                // For each constraint with scope="data_access" and entityId != "*":
                //   call itemService.getItem(entityId) to get:
                //   - accessPolicy (OPEN/SECURE/etc.)
                //   - applicableFilters
                //   - allowedAttributes
                //   - resourceServerUrl
                //   - ownerUserId
                //   - type

                List<Future<JsonObject>> resourceFutures = constraints.stream()
                    .filter(c -> "data_access".equals(c.scope()))
                    .filter(c -> !"*".equals(c.entityId()))
                    .map(c -> itemService.getItem(c.entityId()))
                    .collect(Collectors.toList());

                return CompositeFuture.all(new ArrayList<>(resourceFutures))
                    .map(cf -> buildResponse(app, constraints, cf.list()));
            });
    }

    private JsonObject buildResponse(AppCredentials app,
                                      List<AppConstraints> constraints,
                                      List<JsonObject> resourceDetails) {
        // Build the rich response JSON
        JsonArray constraintsArray = new JsonArray();
        for (AppConstraints c : constraints) {
            constraintsArray.add(new JsonObject()
                .put("scope", c.scope())
                .put("entityType", c.entityType())
                .put("entityId", c.entityId()));
        }

        JsonArray resourcesArray = new JsonArray();
        for (JsonObject rd : resourceDetails) {
            resourcesArray.add(new JsonObject()
                .put("entityId", rd.getString("id"))
                .put("accessPolicy", rd.getString("accessPolicy"))
                .put("applicableFilters", rd.getJsonArray("applicableFilters", new JsonArray()))
                .put("allowedAttributes", rd.getJsonArray("allowedAttributes", new JsonArray()))
                .put("resourceServerUrl", rd.getString("resourceServerUrl", ""))
                .put("ownerUserId", rd.getString("ownerUserId", ""))
                .put("type", rd.getString("type", "")));
        }

        return new JsonObject()
            .put("appId", app.appId().toString())
            .put("userId", app.userId().toString())
            .put("role", app.role())
            .put("status", app.status())
            .put("expiresAt", app.expiryAt())
            .put("constraints", constraintsArray)
            .put("accessibleResources", resourcesArray);
    }
}
```

### 4.4 AppVerifyController

```java
public class AppVerifyController implements DxController {

    private static final String OP_POST_APP_VERIFY = "post-auth-v2-app-verify";
    private final AppVerifyService appVerifyService;

    @Override
    public void register(RouterBuilder builder) {
        builder.operation(OP_POST_APP_VERIFY)
            .handler(this::handleVerify);
    }

    private void handleVerify(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        String appId = body.getString("appId");

        if (appId == null || appId.isBlank()) {
            ctx.response().setStatusCode(400)
                .end(errorResponse("appId is required").encode());
            return;
        }

        appVerifyService.verify(appId)
            .onSuccess(result -> {
                ctx.response().setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("type", "urn:dx:controlPanel:success")
                        .put("title", "Success")
                        .put("result", result)
                        .encode());
            })
            .onFailure(err -> {
                int status = mapErrorToStatus(err);
                ctx.response().setStatusCode(status)
                    .end(errorResponse(err.getMessage()).encode());
            });
    }
}
```

### 4.5 OpenAPI Spec Addition (`app.yaml`)

```yaml
/iudx/auth/v2/app/verify:
  post:
    operationId: post-auth-v2-app-verify
    summary: Verify an app credential and return authorization context
    description: |
      Internal endpoint (service-to-service only).
      Validates an appId and returns the full authorization context
      including user info, constraints, and accessible resource details.
    tags:
      - App Credentials
    requestBody:
      required: true
      content:
        application/json:
          schema:
            type: object
            required: [appId]
            properties:
              appId:
                type: string
                format: uuid
    responses:
      '200':
        description: App verified successfully
      '401':
        description: App expired
      '403':
        description: App revoked or disabled
      '404':
        description: App not found
```

### 4.6 Factory Wiring (`AppVerifyControllerFactory.java`)

```java
public class AppVerifyControllerFactory {
    public static DxController create(
            PostgresService pgService,
            ItemService itemService,
            KeycloakUserService keycloakUserService) {

        AppCredentialsDAO appCredentialsDAO = new AppCredentialsDAOImpl(pgService);
        AppConstraintsDAO appConstraintsDAO = new AppConstraintsDAOImpl(pgService);

        AppVerifyService service = new AppVerifyServiceImpl(
            appCredentialsDAO, appConstraintsDAO, itemService, keycloakUserService);

        return new AppVerifyController(service);
    }
}
```

Register in `ControllerFactory.java`:
```java
controllers.add(
    AppVerifyControllerFactory.create(
        infra.pgService(),
        shared.itemService(),
        shared.keycloakUserService()
    )
);
```

---

## 5. Dataplane: Deep Implementation Details

### 5.1 AppAuthContext Record (Parallel to JwtData)

```java
package org.cdpg.dx.auth.appcredential.model;

/**
 * Authorization context built from controlplane /app/verify response.
 * This is the appId equivalent of JwtData.
 *
 * Every field maps to a specific downstream handler need:
 *   - role      → AuthorizationHandler (role check)
 *   - userId    → ProviderValidationHandler (owner check)
 *   - constraints.entityId → AuthorizationServiceImpl (resource match)
 *   - accessibleResources  → ItemAccessApplicableFilterHandler (skip controlplane call)
 */
public record AppAuthContext(
    String appId,
    String userId,           // maps to JwtData.sub()
    String role,             // maps to JwtData.role()
    String status,
    String expiresAt,
    List<AppConstraint> constraints,
    List<AccessibleResource> accessibleResources
) {
    /**
     * Find the constraint that matches a given resource ID.
     * Checks exact match first, then wildcard.
     */
    public Optional<AppConstraint> findConstraintForResource(String resourceId) {
        return constraints.stream()
            .filter(c -> c.entityId().equalsIgnoreCase(resourceId)
                      || "*".equals(c.entityId()))
            .findFirst();
    }

    /**
     * Find accessible resource details for a given resource ID.
     */
    public Optional<AccessibleResource> findResource(String resourceId) {
        return accessibleResources.stream()
            .filter(r -> r.entityId().equalsIgnoreCase(resourceId))
            .findFirst();
    }

    /**
     * Check if this appId has a specific scope for a resource.
     */
    public boolean hasScope(String resourceId, String scope) {
        return constraints.stream()
            .anyMatch(c ->
                (c.entityId().equalsIgnoreCase(resourceId) || "*".equals(c.entityId()))
                && (c.scope().equals(scope) || "*".equals(c.scope())));
    }
}
```

```java
public record AppConstraint(
    String scope,          // "data_access", "asset_management", "*"
    String entityType,     // "adex:Resource", "adex:DataBank", "*"
    String entityId        // resource UUID or "*"
) {}
```

```java
public record AccessibleResource(
    String entityId,
    String accessPolicy,           // "OPEN", "SECURE", etc.
    List<String> applicableFilters, // ["temporal", "attr", "geo"]
    List<String> allowedAttributes, // ["speed", "temperature"]
    String resourceServerUrl,
    String ownerUserId,
    String type                    // "adex:Resource", "adex:ResourceGroup"
) {}
```

### 5.2 RsRoutingContextHelper — New Methods

```java
// Add to existing RsRoutingContextHelper.java:

private static final String APP_AUTH_CONTEXT = "appAuthContext";
private static final String AUTH_METHOD = "authMethod";

public static void setAppAuthContext(RoutingContext ctx, AppAuthContext appAuthContext) {
    ctx.put(APP_AUTH_CONTEXT, appAuthContext);
    ctx.put(AUTH_METHOD, "appId");
}

public static Optional<AppAuthContext> getAppAuthContext(RoutingContext ctx) {
    return Optional.ofNullable(ctx.get(APP_AUTH_CONTEXT));
}

public static boolean isAppIdAuth(RoutingContext ctx) {
    return "appId".equals(ctx.get(AUTH_METHOD));
}

public static boolean isJwtAuth(RoutingContext ctx) {
    return !"appId".equals(ctx.get(AUTH_METHOD));
}
```

### 5.3 AppCredentialAuthClient

```java
package org.cdpg.dx.auth.appcredential;

public class AppCredentialAuthClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppCredentialAuthClient.class);

    private final WebClient webClient;
    private final String verifyUrl;  // full URL: baseUrl + "/iudx/auth/v2/app/verify"

    public AppCredentialAuthClient(Vertx vertx, String controlplaneBaseUrl, String verifyEndpoint) {
        this.webClient = WebClient.create(vertx, new WebClientOptions().setTrustAll(true));
        this.verifyUrl = controlplaneBaseUrl + verifyEndpoint;
    }

    public Future<AppAuthContext> verify(String appId) {
        JsonObject body = new JsonObject().put("appId", appId);

        return webClient.postAbs(verifyUrl)
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(body)
            .compose(response -> {
                int status = response.statusCode();
                if (status == 200) {
                    JsonObject result = response.bodyAsJsonObject().getJsonObject("result");
                    return Future.succeededFuture(parseAppAuthContext(result));
                } else if (status == 404) {
                    return Future.failedFuture(new DxNotFoundException("AppId not found"));
                } else if (status == 401) {
                    return Future.failedFuture(new DxUnauthorizedException("AppId expired"));
                } else if (status == 403) {
                    return Future.failedFuture(new DxForbiddenException("AppId revoked or disabled"));
                } else {
                    return Future.failedFuture(new DxInternalException(
                        "App verification failed: " + status));
                }
            });
    }

    private AppAuthContext parseAppAuthContext(JsonObject json) {
        List<AppConstraint> constraints = new ArrayList<>();
        JsonArray consArr = json.getJsonArray("constraints", new JsonArray());
        for (int i = 0; i < consArr.size(); i++) {
            JsonObject c = consArr.getJsonObject(i);
            constraints.add(new AppConstraint(
                c.getString("scope"),
                c.getString("entityType"),
                c.getString("entityId")));
        }

        List<AccessibleResource> resources = new ArrayList<>();
        JsonArray resArr = json.getJsonArray("accessibleResources", new JsonArray());
        for (int i = 0; i < resArr.size(); i++) {
            JsonObject r = resArr.getJsonObject(i);
            resources.add(new AccessibleResource(
                r.getString("entityId"),
                r.getString("accessPolicy"),
                toStringList(r.getJsonArray("applicableFilters", new JsonArray())),
                toStringList(r.getJsonArray("allowedAttributes", new JsonArray())),
                r.getString("resourceServerUrl", ""),
                r.getString("ownerUserId", ""),
                r.getString("type", "")));
        }

        return new AppAuthContext(
            json.getString("appId"),
            json.getString("userId"),
            json.getString("role"),
            json.getString("status"),
            json.getString("expiresAt"),
            constraints,
            resources);
    }

    private List<String> toStringList(JsonArray arr) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            list.add(arr.getString(i));
        }
        return list;
    }
}
```

### 5.4 AuthenticationHandler

```java
package org.cdpg.dx.auth.appcredential.handler;

public class AuthenticationHandler implements Handler<RoutingContext> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthenticationHandler.class);

    private final AppCredentialAuthClient appCredentialAuthClient;
    private final Cache<String, AppAuthContext> cache;  // Caffeine cache, nullable

    public AuthenticationHandler(AppCredentialAuthClient client,
                                  boolean cacheEnabled,
                                  int cacheTtlSeconds,
                                  int cacheMaxSize) {
        this.appCredentialAuthClient = client;
        if (cacheEnabled) {
            this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(cacheTtlSeconds))
                .maximumSize(cacheMaxSize)
                .build();
        } else {
            this.cache = null;
        }
    }

    @Override
    public void handle(RoutingContext context) {
        // Check for Bearer token first (JWT takes priority)
        String authHeader = context.request().getHeader("Authorization");
        boolean hasBearerToken = authHeader != null
            && authHeader.toLowerCase().startsWith("bearer ");

        String appId = context.request().getHeader("appId");

        if (hasBearerToken) {
            // ── JWT PATH: pass through, existing handlers will process ──
            context.put("authMethod", "jwt");
            context.next();

        } else if (appId != null && !appId.isBlank()) {
            // ── APPID PATH: verify with controlplane ──
            handleAppIdAuth(context, appId.trim());

        } else {
            // ── NO CREDENTIALS ──
            context.response().setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("type", 401)
                    .put("title", "Not Authorized")
                    .put("detail", "No authentication credentials provided. "
                        + "Supply either 'Authorization: Bearer <JWT>' header "
                        + "or 'appId' header.")
                    .encode());
        }
    }

    private void handleAppIdAuth(RoutingContext context, String appId) {
        // Check cache first
        if (cache != null) {
            AppAuthContext cached = cache.getIfPresent(appId);
            if (cached != null) {
                LOGGER.debug("AppId {} found in cache", appId);
                RsRoutingContextHelper.setAppAuthContext(context, cached);
                context.next();
                return;
            }
        }

        // Call controlplane
        appCredentialAuthClient.verify(appId)
            .onSuccess(appAuthContext -> {
                LOGGER.debug("AppId {} verified successfully", appId);
                if (cache != null) {
                    cache.put(appId, appAuthContext);
                }
                RsRoutingContextHelper.setAppAuthContext(context, appAuthContext);
                context.next();
            })
            .onFailure(err -> {
                LOGGER.error("AppId {} verification failed: {}", appId, err.getMessage());
                int status = 401;
                if (err instanceof DxNotFoundException) status = 401;
                else if (err instanceof DxForbiddenException) status = 403;
                else if (err instanceof DxInternalException) status = 503;

                context.response().setStatusCode(status)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("type", status)
                        .put("title", status == 503 ? "Service Unavailable" : "Not Authorized")
                        .put("detail", err.getMessage())
                        .encode());
            });
    }
}
```

---

## 6. Data Flow: Handler-by-Handler Walkthrough

### 6.1 Full Comparison: JWT vs AppId Flow

```
                          JWT Flow                      AppId Flow
                          ────────                      ──────────

1. AuditingHandler        Log request                   Log request
                          (unchanged)                   (unchanged)

2. AuthenticationHandler  Detect Bearer token            Detect appId header
   (NEW)                  Set authMethod="jwt"          Call POST /app/verify
                          Pass through                  Store AppAuthContext
                                                        Set authMethod="appId"

3. GetIdFromPath          Extract resource ID            Extract resource ID
                          from URL path                 from URL path
                          (unchanged)                   (unchanged)

4. AuthorizationHandler   Extract JWT from header        ── NEW BRANCH ──
   (MODIFIED)             Validate signature             Check authMethod
                          Create JwtData                 If "appId":
                          Check role matches               Get AppAuthContext
                          Store JwtData in context         Check role matches
                                                           (skip JWT extraction)

5. ItemAccessApplicable   ── EXISTING PATH 1 ──         ── NEW PATH 3 ──
   FilterHandler          If principal has "policies":   If authMethod=="appId":
   (MODIFIED)             Extract from JWT claims          Get AppAuthContext
                                                           Use accessibleResources
                          ── EXISTING PATH 2 ──            directly:
                          Else: Call controlplane           - applicableFilters
                          GET /cat/item/access             - allowedAttributes
                          with Bearer token                - accessPolicy
                                                           Store in RoutingContext
                          Both store:                      (NO controlplane call!)
                          - applicableFilter
                          - allowedAttributes            Stores SAME data in
                          - accessPolicy                 RoutingContext
                          - iid
                          - policyId

6. IdValidation           Get accessPolicy               Get accessPolicy
   (UNCHANGED)            If OPEN → pass                 If OPEN → pass
                          Else: check id == iid          Else: check id == iid
                                                         (iid set from AppAuthContext)

7. Business Logic         Uses RoutingContext data        Uses RoutingContext data
   (UNCHANGED)            (filters, attrs, etc.)         (same keys, same format)
```

### 6.2 Key Insight: Convergence Point

Both JWT and AppId flows **converge** after step 5. The RoutingContext contains the same keys:
- `applicableFilter` — same JsonArray format
- `allowedAttributes` — same JsonArray format
- `accessPolicy` — same String value
- `iid` — same String value
- `itemMetaData` — same JsonObject format

This means **IdValidation and Business Logic handlers need zero changes**.

---

## 7. Exact Code Changes Per File

### 7.1 Controlplane Changes

#### `AppVerifyController.java` — NEW
- Package: `org.cdpg.dx.aaa.appCredentials.controller`
- Operation ID: `post-auth-v2-app-verify`
- Extracts `appId` from body, delegates to `AppVerifyService`
- Full code in Section 4.4

#### `AppVerifyService.java` — NEW (interface)
```java
public interface AppVerifyService {
    Future<JsonObject> verify(String appId);
}
```

#### `AppVerifyServiceImpl.java` — NEW
- Validation: status, revocation, expiry (reuse logic from `AppTokenServiceImpl.validateApp()` minus secret check)
- Fetch constraints via `AppConstraintsDAO`
- Fetch resource details via `ItemService`
- Build rich response
- Full code in Section 4.3

#### `AppVerifyControllerFactory.java` — NEW
- Wire DAOs, services, controller
- Full code in Section 4.6

#### `ControllerFactory.java` — MODIFY
- Add `AppVerifyControllerFactory.create(...)` to controllers list

#### `app.yaml` (OpenAPI) — MODIFY
- Add `/iudx/auth/v2/app/verify` endpoint definition
- Full spec in Section 4.5

### 7.2 Dataplane Changes

#### `AppAuthContext.java`, `AppConstraint.java`, `AccessibleResource.java` — NEW
- Package: `org.cdpg.dx.auth.appcredential.model`
- Records holding verification response data
- Full code in Section 5.1

#### `AppCredentialAuthClient.java` — NEW
- Package: `org.cdpg.dx.auth.appcredential`
- HTTP client calling `/app/verify`
- Full code in Section 5.3

#### `AuthenticationHandler.java` — NEW
- Package: `org.cdpg.dx.auth.appcredential.handler`
- Detects JWT vs appId, calls controlplane for appId
- Manages response cache
- Full code in Section 5.4

#### `RsRoutingContextHelper.java` — MODIFY
Add these methods:
```java
// NEW methods
public static void setAppAuthContext(RoutingContext ctx, AppAuthContext appAuthCtx) {
    ctx.put("appAuthContext", appAuthCtx);
    ctx.put("authMethod", "appId");
}

public static Optional<AppAuthContext> getAppAuthContext(RoutingContext ctx) {
    return Optional.ofNullable(ctx.get("appAuthContext"));
}

public static boolean isAppIdAuth(RoutingContext ctx) {
    return "appId".equals(ctx.get("authMethod"));
}
```

#### `AuthorizationHandler` (dx-common) — MODIFY
Current code extracts JWT and checks role. Add appId branch:

```java
@Override
public void handle(RoutingContext context) {
    // ── NEW: Check if this is appId auth (already verified) ──
    if (RsRoutingContextHelper.isAppIdAuth(context)) {
        Optional<AppAuthContext> appCtx = RsRoutingContextHelper.getAppAuthContext(context);
        if (appCtx.isEmpty()) {
            context.fail(401, new DxAuthException("AppAuthContext missing"));
            return;
        }
        // Check role matches required roles
        String appRole = appCtx.get().role();
        boolean roleMatch = allowedRoles.stream()
            .anyMatch(r -> r.getRole().equalsIgnoreCase(appRole));
        if (roleMatch) {
            context.next();
        } else {
            context.fail(403, new DxAuthException("Role not authorized: " + appRole));
        }
        return;
    }

    // ── EXISTING: JWT flow (unchanged) ──
    // ... existing JWT extraction and role validation code ...
}
```

#### `ItemAccessApplicableFilterHandlerNgsild.java` — MODIFY
Add a third path at the top of `handle()`:

```java
@Override
public void handle(RoutingContext context) {
    // ── NEW PATH 3: AppId auth — use pre-fetched data ──
    if (RsRoutingContextHelper.isAppIdAuth(context)) {
        handleAppIdAuth(context);
        return;
    }

    // ── EXISTING PATH 1 & 2 (unchanged) ──
    if (context.user().principal().containsKey("policies")) {
        // Path 1: JWT has policies embedded
        // ... existing code ...
    } else {
        // Path 2: Call controlplane with Bearer token
        // ... existing code ...
    }
}

private void handleAppIdAuth(RoutingContext context) {
    String itemId = RoutingContextHelper.getId(context);
    Optional<AppAuthContext> appCtx = RsRoutingContextHelper.getAppAuthContext(context);

    if (appCtx.isEmpty()) {
        context.fail(401, new DxAuthException("AppAuthContext missing"));
        return;
    }

    AppAuthContext ctx = appCtx.get();

    // Find resource info for the requested item
    Optional<AccessibleResource> resource = ctx.findResource(itemId);

    if (resource.isEmpty()) {
        // Check if wildcard constraint exists
        boolean hasWildcard = ctx.constraints().stream()
            .anyMatch(c -> "*".equals(c.entityId()) &&
                          ("data_access".equals(c.scope()) || "*".equals(c.scope())));
        if (!hasWildcard) {
            context.fail(403, new DxAuthException(
                "AppId does not have access to resource: " + itemId));
            return;
        }
        // Wildcard: allow but with no attribute restrictions
        RoutingContextHelper.setApplicableFilter(context, new JsonArray());
        RoutingContextHelper.setAllowedAttributes(context, new JsonArray());
        RoutingContextHelper.setAccessPolicy(context, "SECURE");
        RoutingContextHelper.setIid(context, itemId);
        context.next();
        return;
    }

    AccessibleResource res = resource.get();

    // Store the SAME RoutingContext data that Path 1 & 2 store
    RoutingContextHelper.setApplicableFilter(context,
        new JsonArray(res.applicableFilters()));
    RoutingContextHelper.setAllowedAttributes(context,
        new JsonArray(res.allowedAttributes()));
    RoutingContextHelper.setAccessPolicy(context, res.accessPolicy());
    RoutingContextHelper.setIid(context, res.entityId());
    RoutingContextHelper.setOwnerUserId(context, res.ownerUserId());

    // Build itemMetaData to match what controlplane would return
    JsonObject itemMetaData = new JsonObject()
        .put("id", res.entityId())
        .put("accessPolicy", res.accessPolicy())
        .put("type", res.type())
        .put("resourceServerUrl", res.resourceServerUrl());
    RoutingContextHelper.setItemMetaData(context, itemMetaData);

    context.next();
}
```

#### `CheckItemAccessHandler.java` — MODIFY
Add appId bypass at the top of `handle()`:

```java
@Override
public void handle(RoutingContext context) {
    // ── NEW: AppId auth — access already verified by /app/verify ──
    if (RsRoutingContextHelper.isAppIdAuth(context)) {
        // The controlplane already validated this appId has access.
        // The accessPolicy is already set in RoutingContext by
        // ItemAccessApplicableFilterHandler.
        context.next();
        return;
    }

    // ── EXISTING: JWT flow (unchanged) ──
    String itemId = RoutingContextHelper.getId(context);
    bearerToken = RoutingContextHelper.getToken(context).orElse(null);
    // ... existing isItemOpen() -> performPostAccessCheck() flow ...
}
```

#### `ResourcePolicyAuthorizationHandler.java` — MODIFY
Add appId branch:

```java
@Override
public void handle(RoutingContext context) {
    String resourceId = RoutingContextHelper.getId(context);

    // ── NEW: AppId auth — check constraints instead of JWT iid ──
    if (RsRoutingContextHelper.isAppIdAuth(context)) {
        Optional<AppAuthContext> appCtx = RsRoutingContextHelper.getAppAuthContext(context);
        if (appCtx.isEmpty()) {
            context.fail(new DxAuthException("AppAuthContext missing"));
            return;
        }

        AppAuthContext ctx = appCtx.get();
        boolean hasAccess = ctx.hasScope(resourceId, "data_access");

        if (hasAccess) {
            context.next();
        } else {
            context.fail(new DxAuthException(
                "AppId does not have data_access scope for resource: " + resourceId));
        }
        return;
    }

    // ── EXISTING: JWT flow (unchanged) ──
    Optional<JwtData> jwtData = RsRoutingContextHelper.getJwtData(context);
    // ... existing authorizationService.authorize(jwtData, resourceId) ...
}
```

#### `ProviderValidationHandler.java` — MODIFY
Add appId branch:

```java
@Override
public void handle(RoutingContext routingContext) {
    // ── NEW: AppId auth ──
    if (RsRoutingContextHelper.isAppIdAuth(routingContext)) {
        Optional<AppAuthContext> appCtx = RsRoutingContextHelper.getAppAuthContext(routingContext);
        if (appCtx.isEmpty()) {
            routingContext.fail(new DxAuthException("AppAuthContext missing"));
            return;
        }

        AppAuthContext ctx = appCtx.get();
        String role = ctx.role();

        // For appId auth, check if the app owner is the resource provider
        if ("provider".equalsIgnoreCase(role)) {
            String id = RoutingContextHelper.getId(routingContext);
            catalogueService.getProviderOwnerId(id)
                .onSuccess(providerUserId -> {
                    if (ctx.userId().equalsIgnoreCase(providerUserId)) {
                        routingContext.next();
                    } else {
                        routingContext.fail(new DxAuthException("Not the resource owner"));
                    }
                })
                .onFailure(routingContext::fail);
        } else {
            routingContext.fail(new DxAuthException("AppId role not authorized for provider operations"));
        }
        return;
    }

    // ── EXISTING: JWT flow (unchanged) ──
    // ... existing code ...
}
```

#### `ApiConstants.java` — MODIFY
```java
// Add:
public static final String HEADER_APP_ID = "appId";

// Update ALLOWED_HEADERS to include:
"appId"
```

#### Controller Classes — MODIFY
All controllers: `LatestController.java`, `DownloadController.java`, `NGSILDSearchController.java`, and any others.

Add `authenticationHandler::handle` as the **first handler** (before `auditingHandler`):

```java
// BEFORE:
builder.operation(POST_LATEST_ENTITY_DATA_SEARCH)
    .handler(auditingHandler::handleApiAudit)
    .handler(getIdFromPathHandler)
    .handler(AuthorizationHandler.forRoles(DxRole.CONSUMER, DxRole.DELEGATE))
    ...

// AFTER:
builder.operation(POST_LATEST_ENTITY_DATA_SEARCH)
    .handler(authenticationHandler::handle)          // ← NEW (first!)
    .handler(auditingHandler::handleApiAudit)
    .handler(getIdFromPathHandler)
    .handler(AuthorizationHandler.forRoles(DxRole.CONSUMER, DxRole.DELEGATE))
    ...
```

#### `IdValidation.java` — NO CHANGE NEEDED
Already works with RoutingContext data (`accessPolicy`, `id`, `iid`). Both JWT and appId flows store the same data.

```java
// Existing code — works for both flows:
String accessPolicy = RoutingContextHelper.getAccessPolicy(event);
String id = RoutingContextHelper.getId(event);
String iid = RoutingContextHelper.getIid(event);

if (accessPolicy.equalsIgnoreCase("Open") || accessPolicy.equalsIgnoreCase("public")) {
    event.next();  // Works for both JWT and appId
} else {
    if (id.equalsIgnoreCase(iid)) {
        event.next();  // id and iid set by both flows
    }
}
```

---

## 8. Security Considerations

### 8.1 Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|-----------|
| AppId leaked (logs, network) | Resource access by unauthorized party | HTTPS only, never log appId values, internal /app/verify |
| UUID brute-force | Attacker guesses valid appId | UUIDv4 = 122 bits entropy (impractical). Rate limit. |
| No proof of possession | Can't prove "ownership" of appId | Accepted trade-off. AppId is a bearer credential like an API key. |
| Replay attacks | Captured appId reused | HTTPS prevents sniffing. Short expiry. |
| /app/verify exposed publicly | Anyone can verify any appId | Internal network only. Optional X-Service-Key. |

### 8.2 Recommended Controls

1. **HTTPS only** — appId must never travel over plain HTTP
2. **Internal endpoint** — `/app/verify` accessible only within service mesh
3. **Rate limiting** — per-appId and per-IP on dataplane
4. **Short expiry** — encourage short-lived appIds for sensitive resources
5. **Audit logging** — log all appId usage
6. **IP allowlisting** (future) — restrict appId usage to specific IPs

---

## 9. Edge Cases & Caching Strategy

### 9.1 Edge Cases

| Case | Behavior |
|------|----------|
| Both Bearer token AND appId header | Bearer token takes priority (JWT flow) |
| Controlplane unreachable | Return 503 Service Unavailable (not 401) |
| AppId valid but resource not in constraints | Return 403 Forbidden |
| Wildcard constraint (`entityId: "*"`) | Allow access but no attribute restrictions |
| Open endpoints (in `openEndPoints` list) | AuthenticationHandler skips auth |
| AppId revoked while cached | Stale cache returns success. Max staleness = cacheTTL. |
| Constraint references resource group | Must resolve group → individual resources in controlplane |

### 9.2 Caching Strategy

**Cache location:** Dataplane, in-memory (Caffeine)
**Cache key:** appId (UUID string)
**Cache value:** `AppAuthContext`
**TTL:** 5 minutes (configurable)
**Max size:** 10,000 entries (configurable)
**Eviction:** LRU when max size reached

**Cache invalidation trade-off:**
- With 5 min TTL: a revoked appId continues working for up to 5 minutes
- For immediate revocation: reduce TTL to 30 seconds (more controlplane calls) or implement pub/sub notification

---

## 10. AppId Capabilities

| Capability | Description |
|-----------|-------------|
| Granular access control | Each appId has specific constraints — different appIds for different resources/operations |
| Usage tracking / analytics | Every request carries appId — track per-app usage patterns |
| Per-app rate limiting | Different rate limits per appId (free tier vs premium) |
| Per-app quotas | E.g., max 10GB download/month per appId |
| Instant revocation | Disable appId → takes effect within cache TTL |
| Live constraint updates | Update app_constraints in DB → next cache miss uses new permissions |
| Audit trail | Complete per-app request history for compliance |
| Multiple apps per user | One user creates multiple appIds with different scopes |

---

## 11. JWT vs AppId Comparison

| Aspect | JWT | AppId |
|--------|-----|-------|
| Client sends | `Authorization: Bearer <token>` | `appId: <uuid>` |
| Auth data lives in | The token (self-contained) | Controlplane database |
| Validation | Signature check (local, fast) | HTTP call to controlplane |
| Revocation | Hard (wait for expiry/blacklist) | Instant (flip status in DB) |
| Client complexity | Handle token refresh | Just send appId |
| Permission changes | Re-issue token | Update DB, takes effect on next request |
| Offline validation | Yes | No |
| Performance | Fast (no network call) | 1 network call (cacheable) |
| Controlplane calls/request | 2-3 (downstream) | 1 (only /app/verify) |

---

## 12. Configuration

### Dataplane `config-dev.json`
```json
{
  "controlplane": {
    "baseUrl": "https://v2.dev.controlplane.iudx.io",
    "appVerifyEndpoint": "/iudx/auth/v2/app/verify"
  },
  "appAuth": {
    "cacheEnabled": true,
    "cacheTtlSeconds": 300,
    "cacheMaxSize": 10000
  }
}
```

### Controlplane (if using service key)
```json
{
  "serviceAuth": {
    "enabled": true,
    "trustedServiceKeys": ["<dataplane-service-key>"]
  }
}
```

### Dataplane `pom.xml` (new dependency)
```xml
<!-- Caffeine cache (if not already present) -->
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
    <version>3.1.8</version>
</dependency>
```

---

## 13. Implementation Order

### Phase 1: Controlplane (1-2 sprints)
1. Create `AppVerifyService` interface
2. Create `AppVerifyServiceImpl` (validation + rich response)
3. Create `AppVerifyController` with `POST /app/verify`
4. Create `AppVerifyControllerFactory`
5. Register in `ControllerFactory.java`
6. Update OpenAPI spec (`app.yaml`)
7. Write unit tests for service
8. Write integration tests for endpoint
9. Deploy to dev

### Phase 2: Dataplane (2-3 sprints)
1. Create `AppAuthContext`, `AppConstraint`, `AccessibleResource` records
2. Create `AppCredentialAuthClient`
3. Create `AuthenticationHandler` with caching
4. Add new methods to `RsRoutingContextHelper`
5. Modify `AuthorizationHandler` (dx-common) — add appId branch
6. Modify `ItemAccessApplicableFilterHandlerNgsild` — add Path 3
7. Modify `CheckItemAccessHandler` — add appId bypass
8. Modify `ResourcePolicyAuthorizationHandler` — add constraint check
9. Modify `ProviderValidationHandler` — add appId branch
10. Update `ApiConstants` and CORS
11. Update all controller route registrations
12. Update `config-dev.json`
13. Update OpenAPI security schemes
14. Add Caffeine dependency to `pom.xml`
15. Write unit tests for each modified handler
16. Write integration tests (end-to-end with controlplane dev)

---

## 14. Files Summary

### Controlplane — New Files (4)
```
src/main/java/org/cdpg/dx/aaa/appCredentials/
  ├── controller/AppVerifyController.java
  ├── service/AppVerifyService.java
  ├── service/impl/AppVerifyServiceImpl.java
  └── factory/AppVerifyControllerFactory.java
```

### Controlplane — Modified Files (2)
```
src/main/java/org/cdpg/dx/common/factory/ControllerFactory.java
docs/controlplane-openapi/paths/app.yaml
```

### Dataplane — New Files (5)
```
src/main/java/org/cdpg/dx/auth/appcredential/
  ├── model/AppAuthContext.java
  ├── model/AppConstraint.java
  ├── model/AccessibleResource.java
  ├── AppCredentialAuthClient.java
  └── handler/AuthenticationHandler.java
```

### Dataplane — Modified Files (10)
```
src/main/java/org/cdpg/dx/common/util/RsRoutingContextHelper.java
src/main/java/org/cdpg/dx/common/constants/ApiConstants.java
src/main/java/org/cdpg/dx/auth/authorization/handler/AuthorizationHandler.java (dx-common)
src/main/java/org/cdpg/dx/rs/authorization/handler/ResourcePolicyAuthorizationHandler.java
src/main/java/org/cdpg/dx/validations/itemandfiltercheck/ItemAccessApplicableFilterHandlerNgsild.java
src/main/java/org/cdpg/dx/rs/util/CheckItemAccessHandler.java
src/main/java/org/cdpg/dx/validations/provider/ProviderValidationHandler.java
src/main/java/org/cdpg/dx/rs/latest/controller/LatestController.java
src/main/java/org/cdpg/dx/rs/download/controller/DownloadController.java
src/main/java/org/cdpg/dx/apiserver/controller/NGSILDSearchController.java (+ any other controllers)
docs/dataplane-openapi/components/security-schemes.yaml
config-dev.json
pom.xml
```

### Files That Need NO Changes (2)
```
src/main/java/org/cdpg/dx/validations/idvalidation/IdValidation.java      (uses RoutingContext — works for both)
Business logic handlers (LatestService, DownloadService, etc.)              (uses RoutingContext — works for both)
```
