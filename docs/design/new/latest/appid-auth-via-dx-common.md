# AppId Authentication — Implementation Design (Verified)

**Feature:** Machine-to-machine (M2M) authentication via AppId + AppSecret  
**Author:** Ankit Singh  
**Date:** 2026-04-08  
**Status:** Draft  

---

## 1. What Already Exists — Read First

### dx-controlplane (`feat/code-modularity-refactor`)

**`aaa.app_credentials` table** (migration V38):
```
app_id          UUID PK
user_id         UUID NOT NULL
app_secret_hash TEXT NOT NULL        ← SHA-512 hash via DigestUtils.sha512Hex()
expiry_at       TIMESTAMP NOT NULL   ← parsed as LocalDateTime, Asia/Kolkata timezone
status          VARCHAR(20)          ← 'active' | 'revoked' | 'expired'
role            VARCHAR              ← added in V45
created_at, modified_at, revoked_at
```

**`app_constraints` table** (migration V45):
```
id          UUID PK
app_id      UUID FK → app_credentials(app_id)
scope       VARCHAR NOT NULL    ← 'data_access' | 'asset_management' | '*' | etc.
entity_type VARCHAR NOT NULL    ← 'adex:Apps' | 'adex:DataBank' | '*' | etc.
entity_id   VARCHAR NOT NULL    ← item UUID or '*' for wildcard
created_at  TIMESTAMP
```

**Fully built Approach A (Token Exchange):**
- `AppCredentialsService` / `AppCredentialsServiceImpl` — CRUD for credentials
- `AppTokenServiceImpl.createToken()` — validates AppId+Secret, resolves scopes→roles, issues signed JWT
- `AppTokenController` — HTTP endpoint at `OP_POST_APP_TOKEN`

No new tables needed. No new migration scripts needed.

---

### dx-common

**`AbstractApiServerVerticle`** — this is where JWT auth is wired, **not** in individual controllers:

```java
JwksResolver jwksResolver = new JwksResolver(
    vertx, config().getJsonObject("issuers"), getJwksInternalProvider());

MultiIssuerJwtAuthHandler authHandler = new MultiIssuerJwtAuthHandler(jwksResolver);
OptionalMultiIssuerJwtAuthHandler optionalAuthHandler =
    new OptionalMultiIssuerJwtAuthHandler(jwksResolver);

routerBuilder.securityHandler("authorization", authHandler);
routerBuilder.securityHandler("optionalAuth",  optionalAuthHandler);
```

Both handlers already exist. They are registered as **OpenAPI security handlers** — they run based on which security scheme is declared in the OpenAPI spec for each route, not via `.handler(...)` calls in controllers.

- `MultiIssuerJwtAuthHandler` → `authorization` scheme → **fails 401 if no Bearer token**
- `OptionalMultiIssuerJwtAuthHandler` → `optionalAuth` scheme → **calls ctx.next() if no Bearer token**

**Current state of `openapi.yaml`** — every route uses:
```yaml
security:
  - authorization: []
```
There is only one security scheme defined (`authorization`, bearer JWT). No `optionalAuth` scheme exists yet in the spec.

---

### dx-dataplane-rs

**`ResourcePolicyAuthorizationHandler`** exists as a class but is **not registered in any controller's handler chain**. The `AuthorizationServiceImpl.idValidation()` code is therefore not executed by any current route.

**`ItemAccessApplicableFilterHandlerNgsild`** — used in NGSILD and Latest routes. Has two paths:

```java
// Fast path — JWT has embedded policies (from AppTokenServiceImpl item fetch)
if (source.containsKey("policies")) {
    // uses: principal.resourceServer, principal.iid, principal.accessPolicy
    // No controlplane HTTP call needed
}

// Slow path — no embedded policies
else {
    bearerToken = RoutingContextHelper.getToken(context).orElse(null);
    // Calls: GET controlplane/iudx/v2/cat/item/access
    //        Authorization: Bearer <bearerToken>
}
```

`RoutingContextHelper.getToken()` reads the Bearer token from the `Authorization` header. For direct AppId requests (no Bearer token), this returns `null` and the call becomes `Authorization: Bearer null` — which controlplane rejects.

---

## 2. The Two Approaches

### Approach A — Token Exchange (already complete)

```
Client
  ↓  POST /api/v1/token  { appId, appSecret, itemId? }
Controlplane (AppTokenController + AppTokenServiceImpl)
  ↓  Issues JWT with realm_access.roles + optionally embedded policies/iid
Client
  ↓  Authorization: Bearer <jwt>
Dataplane — existing handler chain unchanged
```

`AppTokenServiceImpl` does:
1. Fetches `app_credentials` by appId
2. Validates status=active, not expired, `sha512(inputSecret) == app_secret_hash`
3. Fetches `app_constraints`, resolves scopes → roles:

| Scope in `app_constraints` | Roles in `realm_access.roles` |
|-----------------------------|-------------------------------|
| `data_access`               | `consumer`                    |
| `asset_management`          | `provider`                    |
| `user_management`           | `org_admin`, `consumer`       |
| `compute_management`        | `compute`                     |
| `org_admin_access`          | `org_admin`, `consumer`       |
| `cos_admin_access`          | `cos_admin`, `org_admin`, `provider`, `consumer`, `compute` |
| `*` wildcard                | all roles the owner user has  |

4. If `data_access` scope + `itemId` provided → fetches item from catalogue, embeds `policies`, `resourceServer`, `iid`, `accessPolicy` into JWT. This enables the fast path in `ItemAccessApplicableFilterHandlerNgsild`.
5. Issues signed JWT. Token is verified by the dataplane's `MultiIssuerJwtAuthHandler` via JWKS.

**No dataplane code changes needed for Approach A. It works today.**

No `DxRole.APP_ID` is needed — the JWT carries standard roles (`consumer`, `provider`, etc.).

---

### Approach B — Direct AppId at Dataplane (the diagram)

Client sends `X-App-Id` + `X-App-Secret` headers directly to the dataplane. The rest of this document specifies the implementation.

---

## 3. Approach B — Known Gap Before Designing

Before listing changes, one open problem must be understood.

`ItemAccessApplicableFilterHandlerNgsild` (used in NGSILD and Latest routes) has two paths:

- **Fast path**: JWT principal contains `policies` claim → no controlplane HTTP call
- **Slow path**: no `policies` → calls `GET controlplane/iudx/v2/cat/item/access` with Bearer token

For direct AppId auth:
- There is no Bearer token
- The slow path is called with `null` bearer token
- The HTTP call sends `Authorization: Bearer null` → controlplane returns 401

**This means**: direct AppId auth (Approach B) works **only for OPEN access resources** with the current `ItemAccessApplicableFilterHandlerNgsild` implementation, unless one of these solutions is applied:

| Option | Approach |
|--------|---------|
| **B1** | Include item metadata in the gRPC response from controlplane (like AppTokenServiceImpl does), populate `policies`/`iid`/`resourceServer` in principal so fast path triggers |
| **B2** | Add AppId header support to controlplane's `/iudx/v2/cat/item/access` endpoint so the slow path can authenticate with AppId instead of Bearer |
| **B3** | Scope Approach B to OPEN access resources only; SECURE resources must use Approach A |

**This document uses Option B1** — the gRPC response includes item metadata for `data_access` scope. This mirrors what `AppTokenServiceImpl` already does.

---

## 4. Changes in dx-common

**Location:** `/home/ankit/Documents/3-Nov-2022/dx-common`

### 4.1 pom.xml — Add gRPC and Guava

Guava is **not currently in dx-common** (it is in dx-controlplane). Since `AppIdCacheService` uses Guava and lives in dx-common, add it:

```xml
<!-- Guava cache (not currently in dx-common, must add) -->
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
    <version>33.0.0-jre</version>
</dependency>

<!-- gRPC -->
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-netty-shaded</artifactId>
    <version>1.63.0</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-protobuf</artifactId>
    <version>1.63.0</version>
</dependency>
<dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-stub</artifactId>
    <version>1.63.0</version>
</dependency>
<dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java</artifactId>
    <version>3.25.3</version>
</dependency>
<dependency>
    <groupId>javax.annotation</groupId>
    <artifactId>javax.annotation-api</artifactId>
    <version>1.3.2</version>
    <scope>provided</scope>
</dependency>
```

Add the `os-maven-plugin` extension and `protobuf-maven-plugin` in `<build>`:

```xml
<build>
    <extensions>
        <extension>
            <groupId>kr.motd.maven</groupId>
            <artifactId>os-maven-plugin</artifactId>
            <version>1.7.1</version>
        </extension>
    </extensions>
    <plugins>
        <!-- existing plugins ... -->
        <plugin>
            <groupId>org.xolstice.maven.plugins</groupId>
            <artifactId>protobuf-maven-plugin</artifactId>
            <version>0.6.1</version>
            <configuration>
                <protocArtifact>
                    com.google.protobuf:protoc:3.25.3:exe:${os.detected.classifier}
                </protocArtifact>
                <pluginId>grpc-java</pluginId>
                <pluginArtifact>
                    io.grpc:protoc-gen-grpc-java:1.63.0:exe:${os.detected.classifier}
                </pluginArtifact>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>
                        <goal>compile-custom</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

The existing `build-helper-maven-plugin` in dx-common picks up generated sources automatically.

### 4.2 Proto File

**New file:** `src/main/proto/appid_verification.proto`

```protobuf
syntax = "proto3";

package org.cdpg.dx.auth.appid.v1;

option java_multiple_files  = true;
option java_package         = "org.cdpg.dx.auth.appid.v1";
option java_outer_classname = "AppIdVerificationProto";

// ── Request ────────────────────────────────────────────────────
message VerifyAppIdRequest {
    string app_id     = 1;   // UUID string — safe to log
    string app_secret = 2;   // plaintext — NEVER log
}

// ── Response ───────────────────────────────────────────────────
message VerifyAppIdResponse {
    bool   success    = 1;
    string error_code = 2;   // empty on success
                             // INVALID_CREDENTIALS | REVOKED | EXPIRED
    AppIdPrincipalProto principal = 3;
}

message AppIdPrincipalProto {
    string          app_id           = 1;
    string          owner_id         = 2;  // user_id from app_credentials
    repeated string roles            = 3;  // resolved e.g. ["consumer"]
    repeated string scopes           = 4;  // raw scopes e.g. ["data_access"]
    string          item_metadata_json = 5; // JSON of item info (policies, iid, resourceServer,
                                            // accessPolicy) — populated only when data_access
                                            // scope applies and entity_id is a valid UUID
                                            // Empty string = no item metadata available
    int64           expires_at_epoch = 6;  // 0 = no expiry
}

// ── Service ────────────────────────────────────────────────────
service AppIdVerificationService {
    rpc VerifyAppId (VerifyAppIdRequest) returns (VerifyAppIdResponse);
}
```

> **Why `item_metadata_json`?** `ItemAccessApplicableFilterHandlerNgsild` takes the fast path only when `policies` is present in the JWT principal. By embedding item metadata in the gRPC response (same as `AppTokenServiceImpl` does when issuing JWTs), the dataplane can populate this field in the principal and hit the fast path — no bearer token needed.

### 4.3 `AppIdPrincipal.java`

**New file:** `src/main/java/org/cdpg/dx/auth/appid/model/AppIdPrincipal.java`

```java
package org.cdpg.dx.auth.appid.model;

import org.cdpg.dx.auth.appid.v1.AppIdPrincipalProto;
import java.util.List;

public record AppIdPrincipal(
    String       appId,
    String       ownerId,           // user_id from app_credentials
    List<String> roles,             // resolved roles e.g. ["consumer"]
    List<String> scopes,            // raw scopes e.g. ["data_access"]
    String       itemMetadataJson,  // JSON string with policies/iid/resourceServer or ""
    long         expiresAtEpoch     // 0 = no expiry
) {
    public static AppIdPrincipal fromProto(AppIdPrincipalProto proto) {
        return new AppIdPrincipal(
            proto.getAppId(),
            proto.getOwnerId(),
            proto.getRolesList(),
            proto.getScopesList(),
            proto.getItemMetadataJson(),
            proto.getExpiresAtEpoch()
        );
    }

    public boolean hasItemMetadata() {
        return itemMetadataJson != null && !itemMetadataJson.isBlank();
    }
}
```

### 4.4 `AppIdCacheService.java`

**New file:** `src/main/java/org/cdpg/dx/auth/appid/cache/AppIdCacheService.java`

```java
package org.cdpg.dx.auth.appid.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.cdpg.dx.auth.appid.model.AppIdPrincipal;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * In-process cache keyed by appId.
 * TTL default 5 minutes — bounds revocation propagation window.
 * Cache key is appId only, not appId+secret.
 *
 * NOTE: Guava must be added to dx-common pom.xml (it is in dx-controlplane
 * at v33.0.0-jre but not currently in dx-common).
 */
public class AppIdCacheService {

    private final Cache<String, AppIdPrincipal> cache;

    public AppIdCacheService(int maxSize, long ttlMinutes) {
        this.cache = CacheBuilder.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
            .build();
    }

    public Optional<AppIdPrincipal> get(String appId) {
        return Optional.ofNullable(cache.getIfPresent(appId));
    }

    public void put(String appId, AppIdPrincipal principal) {
        cache.put(appId, principal);
    }

    public void invalidate(String appId) {
        cache.invalidate(appId);
    }
}
```

### 4.5 `AppIdVerificationClient.java`

**New file:** `src/main/java/org/cdpg/dx/auth/appid/client/AppIdVerificationClient.java`

```java
package org.cdpg.dx.auth.appid.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.cdpg.dx.auth.appid.v1.AppIdVerificationServiceGrpc;
import org.cdpg.dx.auth.appid.v1.VerifyAppIdRequest;
import org.cdpg.dx.auth.appid.v1.VerifyAppIdResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Async gRPC stub wrapper returning Vert.x Futures.
 * Uses non-blocking async stub — gRPC callbacks do not block the event-loop.
 */
public class AppIdVerificationClient {

    private static final Logger LOG =
        LoggerFactory.getLogger(AppIdVerificationClient.class);

    private final AppIdVerificationServiceGrpc.AppIdVerificationServiceStub asyncStub;

    public AppIdVerificationClient(String host, int port) {
        ManagedChannel channel = ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()   // use TLS in production
            .keepAliveTime(30, TimeUnit.SECONDS)
            .build();
        this.asyncStub = AppIdVerificationServiceGrpc.newStub(channel);
    }

    /** @param appSecret plaintext — NEVER log */
    public Future<VerifyAppIdResponse> verify(String appId, String appSecret) {
        Promise<VerifyAppIdResponse> promise = Promise.promise();
        asyncStub.verifyAppId(
            VerifyAppIdRequest.newBuilder().setAppId(appId).setAppSecret(appSecret).build(),
            new StreamObserver<>() {
                @Override public void onNext(VerifyAppIdResponse r) { promise.complete(r); }
                @Override public void onError(Throwable t) {
                    LOG.error("gRPC VerifyAppId error appId={}", appId, t);
                    promise.fail(t);
                }
                @Override public void onCompleted() { /* no-op */ }
            }
        );
        return promise.future();
    }
}
```

**No changes to `DxRole.java`** — roles are returned as strings from gRPC matching existing enum values. No new role needed.

### 4.6 `AbstractApiServerVerticle.java` — Register AppId Security Handler

**File:** `src/main/java/org/cdpg/dx/apiserver/AbstractApiServerVerticle.java`

JWT auth is wired here as OpenAPI security handlers, not in individual controllers. Add AppId registration in the same block:

```java
// Existing
routerBuilder.securityHandler("authorization", authHandler);
routerBuilder.securityHandler("optionalAuth",  optionalAuthHandler);

// NEW — add after the existing two lines
// AppIdAuthHandler is instantiated by subclasses that support AppId auth.
// AbstractApiServerVerticle provides a hook; default returns null (no-op).
Handler<RoutingContext> appIdHandler = getAppIdAuthHandler();
if (appIdHandler != null) {
    routerBuilder.securityHandler("appIdAuth", appIdHandler);
}
```

Add a protected method for subclasses to override:

```java
/** Override in subclasses that support AppId authentication. */
protected Handler<RoutingContext> getAppIdAuthHandler() {
    return null;
}
```

---

## 5. Changes in dx-dataplane-rs (`openapi.yaml` + `ApiServerVerticle`)

### 5.1 `openapi.yaml` — Add `appIdAuth` Security Scheme and Update Routes

**File:** `docs/openapi.yaml`

Add the new scheme to `components/securitySchemes`:

```yaml
components:
  securitySchemes:
    authorization:          # existing — requires Bearer token
      type: http
      scheme: bearer
      bearerFormat: JWT
    optionalAuth:           # existing handler, new scheme name in spec
      type: http
      scheme: bearer
      bearerFormat: JWT
    appIdAuth:              # NEW — X-App-Id / X-App-Secret headers
      type: apiKey
      in: header
      name: X-App-Id
```

For routes that should accept AppId credentials, replace:
```yaml
security:
  - authorization: []
```
With:
```yaml
security:
  - authorization: []   # JWT path
  - appIdAuth: []       # AppId path (OR logic in OpenAPI)
```

> Vert.x OpenAPI treats multiple items in the `security` array as OR — only one needs to pass. Both handlers run in registration order; the first that sets `ctx.user()` wins. Since `optionalAuth` is registered first and calls `ctx.next()` on missing token, and `appIdAuth` only runs for AppId headers, this works correctly.

> **Tip:** For the JWT-only routes (admin, publish), leave `authorization: []` unchanged.

### 5.2 `ApiServerVerticle.java` — Override `getAppIdAuthHandler()`

**File:** `src/main/java/org/cdpg/dx/apiserver/ApiServerVerticle.java`

```java
@Override
protected Handler<RoutingContext> getAppIdAuthHandler() {
    JsonObject appIdCacheConfig = config().getJsonObject("appIdCache", new JsonObject());
    int  maxSize    = appIdCacheConfig.getInteger("maxSize",    5000);
    long ttlMinutes = appIdCacheConfig.getLong("ttlMinutes",    5L);

    String controlplaneHost = config().getString("controlplaneHost");
    int    controlplaneGrpc = config().getInteger("controlplaneGrpcPort", 8090);

    AppIdCacheService       cache  = new AppIdCacheService(maxSize, ttlMinutes);
    AppIdVerificationClient client = new AppIdVerificationClient(controlplaneHost, controlplaneGrpc);

    return new AppIdAuthHandler(cache, client);
}
```

### 5.3 `AppIdAuthHandler.java`

**New file:**
`src/main/java/org/cdpg/dx/auth/appid/handler/AppIdAuthHandler.java`

```java
package org.cdpg.dx.auth.appid.handler;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import org.cdpg.dx.auth.appid.cache.AppIdCacheService;
import org.cdpg.dx.auth.appid.client.AppIdVerificationClient;
import org.cdpg.dx.auth.appid.model.AppIdPrincipal;
import org.cdpg.dx.common.exception.DxForbiddenException;
import org.cdpg.dx.common.exception.DxUnauthorizedException;
import org.cdpg.dx.common.model.JwtData;
import org.cdpg.dx.common.util.RsRoutingContextHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAPI security handler for AppId/AppSecret authentication.
 * Registered under scheme name "appIdAuth" in AbstractApiServerVerticle.
 *
 * Only runs when the security scheme "appIdAuth" is applied to a route
 * in openapi.yaml. If ctx.user() is already set (JWT path succeeded first),
 * this handler is a no-op.
 *
 * On success, populates:
 *   ctx.user()  — principal with realm_access.roles + optionally embedded
 *                 item metadata (policies, resourceServer, iid, accessPolicy)
 *   JwtData     — stored via RsRoutingContextHelper for downstream handlers
 *
 * JwtData is built with the canonical record constructor (not the JSON
 * constructor which requires access_token to be non-null).
 */
public class AppIdAuthHandler implements Handler<RoutingContext> {

    private static final Logger LOG = LoggerFactory.getLogger(AppIdAuthHandler.class);

    static final String HEADER_APP_ID     = "X-App-Id";
    static final String HEADER_APP_SECRET = "X-App-Secret";

    private final AppIdCacheService       cache;
    private final AppIdVerificationClient grpcClient;

    public AppIdAuthHandler(AppIdCacheService cache, AppIdVerificationClient grpcClient) {
        this.cache      = cache;
        this.grpcClient = grpcClient;
    }

    @Override
    public void handle(RoutingContext ctx) {
        // JWT auth already set user — skip
        if (ctx.user() != null) {
            ctx.next();
            return;
        }

        String appId     = ctx.request().getHeader(HEADER_APP_ID);
        String appSecret = ctx.request().getHeader(HEADER_APP_SECRET);

        if (appId == null || appSecret == null) {
            ctx.fail(new DxUnauthorizedException("Missing authentication credentials"));
            return;
        }
        if (!isValidUuid(appId)) {
            ctx.fail(new DxUnauthorizedException("Invalid X-App-Id format"));
            return;
        }

        // Cache hit
        var cached = cache.get(appId);
        if (cached.isPresent()) {
            LOG.debug("AppId cache HIT appId={}", appId);
            populateContext(ctx, cached.get());
            ctx.next();
            return;
        }

        // Cache miss — call controlplane
        LOG.debug("AppId cache MISS appId={}", appId);
        grpcClient.verify(appId, appSecret)
            .onSuccess(response -> {
                if (!response.getSuccess()) {
                    String code = response.getErrorCode();
                    LOG.warn("AppId rejected appId={} reason={}", appId, code);
                    ctx.fail(toException(code));
                    return;
                }
                AppIdPrincipal principal = AppIdPrincipal.fromProto(response.getPrincipal());
                cache.put(appId, principal);
                populateContext(ctx, principal);
                ctx.next();
            })
            .onFailure(err -> {
                LOG.error("gRPC verification failed", err);
                ctx.fail(new RuntimeException("Authentication service unavailable"));
            });
    }

    /**
     * Builds ctx.user() so that downstream handlers work unchanged.
     *
     * Principal JSON shape (matches what MultiIssuerJwtAuthHandler produces):
     *   realm_access.roles  ← roles resolved by controlplane (e.g. ["consumer"])
     *   sub                 ← ownerId
     *   iss                 ← "appid" sentinel
     *
     * If item metadata is embedded in the principal (Option B1 — gRPC returned it),
     * also add: policies, resourceServer, iid, accessPolicy
     * This enables ItemAccessApplicableFilterHandlerNgsild's fast path.
     *
     * JwtData uses canonical constructor — access_token is null (safe, because
     * RsRoutingContextHelper.getJwtData() is only checked by
     * ResourcePolicyAuthorizationHandler which is not in any current route chain).
     */
    private void populateContext(RoutingContext ctx, AppIdPrincipal principal) {
        JsonObject principalJson = new JsonObject()
            .put("sub",          principal.ownerId())
            .put("iss",          "appid")
            .put("realm_access", new JsonObject()
                .put("roles", new JsonArray(principal.roles())));

        // If gRPC response included item metadata, merge it into the principal.
        // This enables ItemAccessApplicableFilterHandlerNgsild to take the fast
        // path (policies present → no bearer token needed).
        if (principal.hasItemMetadata()) {
            JsonObject itemMeta = new JsonObject(principal.itemMetadataJson());
            principalJson.mergeIn(itemMeta);
        }

        ctx.setUser(User.fromName(principal.ownerId()).attributes(principalJson));

        // JwtData — use canonical constructor, NOT the JSON one (which requires access_token)
        JwtData jwtData = new JwtData(
            /* accessToken */ null,
            /* sub         */ principal.ownerId(),
            /* iss         */ "appid",
            /* aud         */ null,
            /* exp         */ principal.expiresAtEpoch() == 0L ? null : principal.expiresAtEpoch(),
            /* iat         */ System.currentTimeMillis() / 1000L,
            /* iid         */ principal.hasItemMetadata()
                                  ? new JsonObject(principal.itemMetadataJson()).getString("iid")
                                  : null,
            /* role        */ principal.roles().isEmpty() ? "consumer" : principal.roles().get(0),
            /* cons        */ new JsonObject(),
            /* drl         */ null,
            /* did         */ null,
            /* expiry      */ null
        );
        RsRoutingContextHelper.setJwtData(ctx, jwtData);
    }

    private Throwable toException(String errorCode) {
        return switch (errorCode) {
            case "REVOKED"  -> new DxForbiddenException("AppId credential has been revoked");
            case "EXPIRED"  -> new DxForbiddenException("AppId credential has expired");
            default         -> new DxUnauthorizedException("Invalid AppId credentials");
        };
    }

    private boolean isValidUuid(String s) {
        try { java.util.UUID.fromString(s); return true; }
        catch (IllegalArgumentException e) { return false; }
    }
}
```

### 5.4 Config Addition

```json
{
  "controlplaneHost":     "controlplane.iudx.io",
  "controlplaneGrpcPort": 8090,
  "appIdCache": {
    "maxSize":    5000,
    "ttlMinutes": 5
  }
}
```

---

## 6. Changes in dx-controlplane

### 6.1 pom.xml

gRPC comes transitively from dx-common once dx-common is updated.
`commons-codec` (for `DigestUtils.sha512Hex`) is already used by `AppTokenServiceImpl`.

No new direct dependencies needed unless dx-common is not already on the classpath.

### 6.2 No New Database Tables

Both tables (`aaa.app_credentials`, `app_constraints`) already exist. No migrations needed.

### 6.3 `AppIdVerificationGrpcService.java`

**New file:**
`src/main/java/org/cdpg/dx/aaa/appid/grpc/AppIdVerificationGrpcService.java`

Wraps existing `AppCredentialsService` and `ItemService`. The item fetch logic is the same as `AppTokenServiceImpl.fetchAndValidateItemForApp()`.

```java
package org.cdpg.dx.aaa.appid.grpc;

import io.grpc.stub.StreamObserver;
import io.vertx.core.Future;
import org.apache.commons.codec.digest.DigestUtils;
import org.cdpg.dx.aaa.appCredentials.model.AppConstraints;
import org.cdpg.dx.aaa.appCredentials.model.AppCredentials;
import org.cdpg.dx.aaa.appCredentials.service.AppCredentialsService;
import org.cdpg.dx.aaa.item.service.ItemService;
import org.cdpg.dx.aaa.item.util.GetItemRequest;
import org.cdpg.dx.aaa.token.model.ItemInfo;
import org.cdpg.dx.auth.appid.v1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;

/**
 * gRPC service for AppId verification.
 *
 * Validation reuses the exact same logic as AppTokenServiceImpl:
 * - status check (app.revokedAt() != null → REVOKED, status != active → EXPIRED)
 * - expiry: LocalDateTime.parse(app.expiryAt()).atZone(ZoneId.of("Asia/Kolkata"))
 * - secret: DigestUtils.sha512Hex(inputSecret).equals(app.appSecret())
 * - scope→role mapping: identical table to AppTokenServiceImpl.issueAppToken()
 * - item metadata: same as AppTokenServiceImpl.fetchAndValidateItemForApp()
 *
 * Does NOT issue a JWT. Returns structured AppIdPrincipalProto.
 */
public class AppIdVerificationGrpcService
    extends AppIdVerificationServiceGrpc.AppIdVerificationServiceImplBase {

    private static final Logger LOG =
        LoggerFactory.getLogger(AppIdVerificationGrpcService.class);

    private final AppCredentialsService appCredentialsService;
    private final ItemService           itemService;

    public AppIdVerificationGrpcService(AppCredentialsService appCredentialsService,
                                         ItemService itemService) {
        this.appCredentialsService = appCredentialsService;
        this.itemService           = itemService;
    }

    @Override
    public void verifyAppId(VerifyAppIdRequest request,
                            StreamObserver<VerifyAppIdResponse> observer) {
        UUID appId;
        try {
            appId = UUID.fromString(request.getAppId());
        } catch (IllegalArgumentException e) {
            respond(observer, failure("INVALID_CREDENTIALS"));
            return;
        }

        appCredentialsService.getAppById(appId)
            .compose(app -> validateAndResolve(app, request.getAppSecret(), appId))
            .onSuccess(resp -> respond(observer, resp))
            .onFailure(err -> {
                LOG.error("Verification error appId={}", appId, err);
                respond(observer, failure("INVALID_CREDENTIALS"));
            });
    }

    private Future<VerifyAppIdResponse> validateAndResolve(AppCredentials app,
                                                            String inputSecret,
                                                            UUID appId) {
        // Revoke check — same as AppTokenServiceImpl.validateApp()
        if (app.revokedAt() != null) {
            return Future.succeededFuture(failure("REVOKED"));
        }
        if (!"active".equalsIgnoreCase(app.status())) {
            return Future.succeededFuture(failure("EXPIRED"));
        }

        // Expiry check — same timezone handling as AppTokenServiceImpl
        Instant expiry = LocalDateTime.parse(app.expiryAt())
            .atZone(ZoneId.of("Asia/Kolkata"))
            .toInstant();
        if (expiry.isBefore(Instant.now())) {
            return Future.succeededFuture(failure("EXPIRED"));
        }

        // Secret check — SHA-512, same as AppTokenServiceImpl.hash()
        if (!DigestUtils.sha512Hex(inputSecret).equals(app.appSecret())) {
            return Future.succeededFuture(failure("INVALID_CREDENTIALS"));
        }

        return appCredentialsService.getAppConstraintsById(appId)
            .compose(constraints -> buildResponse(app, constraints));
    }

    private Future<VerifyAppIdResponse> buildResponse(AppCredentials app,
                                                       List<AppConstraints> constraints) {
        List<String> allScopes = constraints.stream()
            .map(AppConstraints::scope)
            .filter(s -> s != null && !s.isBlank())
            .distinct()
            .toList();

        // Scope → role mapping — identical to AppTokenServiceImpl.issueAppToken()
        Set<String> roles = new HashSet<>();
        roles.add("consumer");
        boolean needsItemFetch = false;

        for (String scope : allScopes) {
            switch (scope.toLowerCase()) {
                case "data_access"        -> { roles.add("consumer"); needsItemFetch = true; }
                case "asset_management"   -> roles.add("provider");
                case "user_management"    -> { roles.add("org_admin"); roles.add("consumer"); }
                case "compute_management" -> roles.add("compute");
                case "org_admin_access"   -> { roles.add("org_admin"); roles.add("consumer"); }
                case "cos_admin_access"   -> roles.addAll(
                    List.of("cos_admin","org_admin","provider","consumer","compute"));
                // "*" wildcard — consumer minimum already added
            }
        }

        Instant expiryInstant = LocalDateTime.parse(app.expiryAt())
            .atZone(ZoneId.of("Asia/Kolkata")).toInstant();

        AppIdPrincipalProto.Builder principalBuilder = AppIdPrincipalProto.newBuilder()
            .setAppId(app.appId().toString())
            .setOwnerId(app.userId().toString())
            .addAllRoles(roles)
            .addAllScopes(allScopes)
            .setExpiresAtEpoch(expiryInstant.getEpochSecond());

        // If data_access scope applies, fetch item metadata so the dataplane's
        // ItemAccessApplicableFilterHandlerNgsild can use the fast path.
        // Same logic as AppTokenServiceImpl.resolveItemId() + fetchAndValidateItemForApp().
        if (needsItemFetch) {
            String entityId = resolveDataAccessEntityId(constraints);
            if (entityId != null && !entityId.isBlank() && !entityId.equals("*")) {
                return fetchItemMetadata(app.userId(), entityId, constraints)
                    .map(itemMetaJson -> {
                        principalBuilder.setItemMetadataJson(itemMetaJson);
                        return successResponse(principalBuilder.build());
                    })
                    .recover(err -> {
                        // Item fetch failed — return response without item metadata.
                        // Dataplane will fall back to controlplane HTTP call (only
                        // works for OPEN resources without bearer token).
                        LOG.warn("Item metadata fetch failed for entityId={}: {}", entityId, err.getMessage());
                        return Future.succeededFuture(successResponse(principalBuilder.build()));
                    });
            }
        }

        return Future.succeededFuture(successResponse(principalBuilder.build()));
    }

    /**
     * Mirrors AppTokenServiceImpl.resolveItemId():
     * Looks for a data_access constraint with a specific entity UUID (not wildcard).
     */
    private String resolveDataAccessEntityId(List<AppConstraints> constraints) {
        Set<String> dataItemTypes = Set.of("adex:Apps", "adex:DataBank", "adex:AiModel");
        return constraints.stream()
            .filter(c -> "data_access".equalsIgnoreCase(c.scope()))
            .filter(c -> dataItemTypes.contains(c.entityType()))
            .filter(c -> c.entityId() != null && isValidUuid(c.entityId()))
            .map(AppConstraints::entityId)
            .findFirst()
            .orElse(null);
    }

    private Future<String> fetchItemMetadata(UUID userId, String entityId,
                                              List<AppConstraints> constraints) {
        GetItemRequest req = new GetItemRequest(entityId, userId.toString());
        return itemService.getItemWithAccessChecks(req)
            .map(response -> {
                var results = response.getResponse().getJsonArray("results");
                if (results == null || results.isEmpty()) {
                    throw new RuntimeException("No item found for entityId: " + entityId);
                }
                var item = results.getJsonObject(0);
                return ItemInfo.fromJson(item).toJson().encode();
            });
    }

    private VerifyAppIdResponse successResponse(AppIdPrincipalProto principal) {
        return VerifyAppIdResponse.newBuilder()
            .setSuccess(true).setPrincipal(principal).build();
    }

    private VerifyAppIdResponse failure(String errorCode) {
        return VerifyAppIdResponse.newBuilder()
            .setSuccess(false).setErrorCode(errorCode).build();
    }

    private void respond(StreamObserver<VerifyAppIdResponse> obs, VerifyAppIdResponse resp) {
        obs.onNext(resp);
        obs.onCompleted();
    }

    private boolean isValidUuid(String s) {
        try { UUID.fromString(s); return true; }
        catch (IllegalArgumentException e) { return false; }
    }
}
```

### 6.4 `GrpcServerVerticle.java`

**New file:**
`src/main/java/org/cdpg/dx/aaa/appid/server/GrpcServerVerticle.java`

```java
package org.cdpg.dx.aaa.appid.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import org.cdpg.dx.aaa.appCredentials.service.AppCredentialsService;
import org.cdpg.dx.aaa.appid.grpc.AppIdVerificationGrpcService;
import org.cdpg.dx.aaa.item.service.ItemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x verticle hosting the gRPC server.
 * Register in the controlplane Deployer alongside existing verticles.
 * Config key: grpcPort (default 8090)
 */
public class GrpcServerVerticle extends AbstractVerticle {

    private static final Logger LOG = LoggerFactory.getLogger(GrpcServerVerticle.class);

    private final AppCredentialsService appCredentialsService;
    private final ItemService           itemService;
    private Server grpcServer;

    public GrpcServerVerticle(AppCredentialsService appCredentialsService,
                               ItemService itemService) {
        this.appCredentialsService = appCredentialsService;
        this.itemService           = itemService;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        int port = config().getInteger("grpcPort", 8090);
        try {
            grpcServer = ServerBuilder.forPort(port)
                .addService(new AppIdVerificationGrpcService(appCredentialsService, itemService))
                .build().start();
            LOG.info("AppId gRPC server started on port {}", port);
            startPromise.complete();
        } catch (Exception e) {
            startPromise.fail(e);
        }
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (grpcServer != null) grpcServer.shutdown();
        stopPromise.complete();
    }
}
```

Register `GrpcServerVerticle` in the controlplane Deployer, passing existing `AppCredentialsService` and `ItemService` instances.

### 6.5 Controlplane Config

```json
{
  "grpcPort": 8090
}
```

---

## 7. Summary of All Changes

### dx-common

| File | Type | Notes |
|------|------|-------|
| `pom.xml` | Modified | Add Guava (not currently present!) + gRPC deps + protobuf-maven-plugin |
| `src/main/proto/appid_verification.proto` | New | Contract: request, response with item metadata |
| `src/main/java/.../auth/appid/model/AppIdPrincipal.java` | New | Domain object from proto |
| `src/main/java/.../auth/appid/cache/AppIdCacheService.java` | New | 5-min Guava cache |
| `src/main/java/.../auth/appid/client/AppIdVerificationClient.java` | New | Async gRPC stub wrapper |
| `src/main/java/.../apiserver/AbstractApiServerVerticle.java` | Modified | Add `getAppIdAuthHandler()` hook + registration |
| `src/main/java/.../auth/authorization/model/DxRole.java` | **No change** | Existing roles sufficient |

### dx-controlplane

| File | Type | Notes |
|------|------|-------|
| `pom.xml` | Modified | Only if dx-common not already on classpath |
| DB migrations | **None** | Tables already exist (V38, V45) |
| `src/main/java/.../aaa/appid/grpc/AppIdVerificationGrpcService.java` | New | Wraps AppCredentialsService + ItemService |
| `src/main/java/.../aaa/appid/server/GrpcServerVerticle.java` | New | gRPC server verticle |
| `Deployer.java` | Modified | Register GrpcServerVerticle |
| `configs/config-dev.json` | Modified | Add `grpcPort` |

### dx-dataplane-rs

| File | Type | Notes |
|------|------|-------|
| `pom.xml` | **No change** | gRPC + new classes arrive via dx-common |
| `docs/openapi.yaml` | Modified | Add `appIdAuth` security scheme; update route security |
| `src/main/java/.../auth/appid/handler/AppIdAuthHandler.java` | New | Security handler; merges item metadata into principal |
| `src/main/java/.../apiserver/ApiServerVerticle.java` | Modified | Override `getAppIdAuthHandler()` |
| `configs/config-dev.json` | Modified | Add controlplaneHost, controlplaneGrpcPort, appIdCache |

---

## 8. Open Questions

| # | Question | Impact | Resolution |
|---|----------|--------|------------|
| 1 | For AppId with wildcard `entity_id = "*"` and `data_access` scope, no specific item is fetched. The dataplane's `ItemAccessApplicableFilterHandlerNgsild` will use the slow path (no bearer token) and fail for SECURE resources. | SECURE resource access with wildcard AppId | **Resolved — B3.** Primary use case is always a specific entity UUID. Wildcard AppId is restricted to OPEN resources only. SECURE resources with wildcard AppId must use Approach A (token exchange). No code changes needed to slow path. |
| 2 | Should multiple `data_access` constraints with different entity IDs be resolved? Currently only the first valid one is fetched. | Apps with access to multiple resources | **Resolved.** gRPC response extended to return a map of `entityId → full item metadata` (policies, iid, resourceServer, accessPolicy) for all allowed entities. A new handler (following existing `GetIdFromBody`/`GetIdFromPath` pattern in dx-common) extracts the requested entity ID from the request (path/query/body) and checks it against the allowed map. If found → merges that entity's metadata into principal → `ItemAccessApplicableFilterHandlerNgsild` fast path triggers unchanged. If not found → 403. Cache stores the full map keyed by AppId. |
| 3 | gRPC channel uses plaintext in dev. TLS configuration for production — should this use mTLS or one-way TLS? | Production security | **Open.** Prod network topology (same cluster vs cross-cluster) not yet confirmed. See `appid-rmq-alternative.md` in this folder for an RMQ-based alternative that avoids gRPC TLS entirely. |
