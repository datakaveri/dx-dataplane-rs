# AppId Authentication — Implementation Design

**Feature:** Machine-to-machine (M2M) authentication via AppId + AppSecret credentials  
**Author:** Ankit Singh  
**Date:** 2026-04-08  
**Status:** Draft  

---

## 1. Problem Statement

The current DX Dataplane only supports human-user JWT tokens issued by Keycloak/controlplane. Machine clients (automated pipelines, IoT daemons, service integrations) cannot easily obtain short-lived JWTs. AppId/AppSecret is a long-lived, revocable credential pair that lets a machine authenticate without a Keycloak session.

This document specifies every code change needed in **dx-dataplane-rs** (dataplane) and **dx-controlplane** to implement AppId-based authentication end-to-end.

---

## 2. Credential Shape

```
X-App-Id:     <uuid-v4>          # stable identifier, safe to log
X-App-Secret: <base64url(32 random bytes)>   # secret, never log
```

The secret is stored as a **bcrypt hash** (work factor ≥ 12) in the controlplane database. Only the hash is persisted; the plaintext is shown once at creation and never stored.

---

## 3. End-to-End Request Flow

```
Client
  │  HTTP POST /ngsi-ld/v2/entityOperations/query
  │  X-App-Id: 550e8400-e29b-41d4-a716-446655440000
  │  X-App-Secret: dGhpcyBpcyBhIHRlc3Qgc2VjcmV0...
  ▼
┌──────────────────────────────────────────────────────────────────┐
│ dx-dataplane-rs  (Vert.x ApiServerVerticle)                      │
│                                                                  │
│  Router chain                                                    │
│  1. JwtAuthHandler (existing)  ← skipped if no Bearer token      │
│  2. AppIdAuthHandler (NEW)                                       │
│     a. Detect X-App-Id / X-App-Secret headers                   │
│     b. Look up AppIdCacheService (Guava, 5 min TTL)             │
│        ├─ HIT  → use cached AppIdVerifyResponse                  │
│        └─ MISS → gRPC VerifyAppId to controlplane               │
│     c. Map response → JwtData + User (AppId flavour)            │
│     d. Store in RoutingContext via RsRoutingContextHelper        │
│     e. context.next()                                           │
│  3. AuthorizationHandler.forRoles(...)  (existing, unchanged)   │
│  4. ResourcePolicyAuthorizationHandler  (existing, minimal mod) │
│  5. Controller handler                                          │
└──────────────────┬───────────────────────────────────────────────┘
                   │  gRPC (on cache miss)
                   │  io.grpc.ManagedChannel
                   ▼
┌──────────────────────────────────────────────────────────────────┐
│ dx-controlplane  (gRPC server on port 8090)                      │
│                                                                  │
│  AppIdVerificationService                                        │
│  rpc VerifyAppId(VerifyAppIdRequest) → VerifyAppIdResponse       │
│  1. SELECT from app_credentials WHERE app_id = ?                │
│  2. Check status = ACTIVE                                        │
│  3. bcrypt.verify(incoming_secret, stored_hash)                 │
│  4. Resolve scopes, constraints, owner_id, resource_server_url  │
│  5. Return structured response (see §6)                         │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Database Schema — Controlplane

### 4.1 New Table: `app_credentials`

```sql
CREATE TABLE app_credentials (
    app_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id           UUID NOT NULL,                    -- references users.id
    resource_server_url TEXT NOT NULL,                   -- e.g. dataplaneX.iudx.io
    secret_hash        TEXT NOT NULL,                    -- bcrypt hash, work factor ≥ 12
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                       -- ACTIVE | REVOKED | EXPIRED
    scopes             TEXT[] NOT NULL DEFAULT '{}',     -- e.g. {read, subscribe}
    constraints        JSONB NOT NULL DEFAULT '{}',      -- arbitrary constraint bag
    label              TEXT,                             -- human-readable name
    expires_at         TIMESTAMPTZ,                      -- NULL = never expires
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_app_credentials_owner  ON app_credentials(owner_id);
CREATE INDEX idx_app_credentials_status ON app_credentials(status);
```

> **Why not `service_accounts`?** A dedicated table keeps AppId credentials isolated from human accounts, simplifying auditing and revocation without touching the users or token tables.

---

## 5. gRPC Proto Definition

Both repos share this proto. Maintain it in a separate `dx-proto` repository and publish as a Maven artifact.

```protobuf
// proto/appid_verification.proto
syntax = "proto3";

package org.cdpg.dx.auth.appid.v1;

option java_multiple_files = true;
option java_package = "org.cdpg.dx.auth.appid.v1";

// ──────────────────────────────────────────────────
// Request
// ──────────────────────────────────────────────────
message VerifyAppIdRequest {
  string app_id     = 1;  // UUID string
  string app_secret = 2;  // base64url-encoded plaintext secret
}

// ──────────────────────────────────────────────────
// Response
// ──────────────────────────────────────────────────
message VerifyAppIdResponse {
  bool   success    = 1;
  string error_code = 2;   // empty on success; e.g. INVALID_CREDENTIALS, REVOKED, EXPIRED
  AppIdPrincipal principal = 3;
}

message AppIdPrincipal {
  string app_id              = 1;
  string owner_id            = 2;   // maps to JwtData.sub
  string resource_server_url = 3;   // maps to User.resourceServerUrl
  repeated string scopes     = 4;   // maps to JwtData — used by AuthorizationHandler
  string constraints_json    = 5;   // serialized JsonObject (JwtData.cons)
  string label               = 6;
  int64  expires_at_epoch    = 7;   // 0 = never expires
}

// ──────────────────────────────────────────────────
// Service
// ──────────────────────────────────────────────────
service AppIdVerificationService {
  rpc VerifyAppId (VerifyAppIdRequest) returns (VerifyAppIdResponse);
}
```

**Maven artifact** (after `mvn deploy` from dx-proto):
```xml
<dependency>
  <groupId>org.cdpg.dx</groupId>
  <artifactId>dx-proto</artifactId>
  <version>1.0.0</version>
</dependency>
```

---

## 6. Controlplane Changes (dx-controlplane)

### 6.1 New Maven Dependencies

Add to `pom.xml`:

```xml
<!-- gRPC server -->
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
<!-- BCrypt -->
<dependency>
  <groupId>org.mindrot</groupId>
  <artifactId>jbcrypt</artifactId>
  <version>0.4</version>
</dependency>
<!-- Proto shared definitions -->
<dependency>
  <groupId>org.cdpg.dx</groupId>
  <artifactId>dx-proto</artifactId>
  <version>1.0.0</version>
</dependency>
```

Also add the `protobuf-maven-plugin` to generate gRPC stubs at build time.

### 6.2 New Files in Controlplane

```
src/main/java/org/cdpg/dx/auth/appid/
├── grpc/
│   └── AppIdVerificationGrpcService.java   ← gRPC service impl
├── repository/
│   └── AppCredentialsRepository.java       ← DB access
├── model/
│   └── AppCredential.java                  ← domain object
└── server/
    └── GrpcServerVerticle.java             ← Vert.x verticle that hosts gRPC server
```

#### 6.2.1 `AppCredential.java`

```java
package org.cdpg.dx.auth.appid.model;

import java.time.Instant;
import java.util.List;

public record AppCredential(
    String appId,
    String ownerId,
    String resourceServerUrl,
    String secretHash,          // bcrypt hash
    String status,              // ACTIVE | REVOKED | EXPIRED
    List<String> scopes,
    String constraintsJson,     // raw JSON string
    String label,
    Instant expiresAt           // null = never
) {}
```

#### 6.2.2 `AppCredentialsRepository.java`

```java
package org.cdpg.dx.auth.appid.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.cdpg.dx.auth.appid.model.AppCredential;

import java.util.Optional;

public class AppCredentialsRepository {

  private static final String FIND_BY_APP_ID = """
      SELECT app_id, owner_id, resource_server_url, secret_hash,
             status, scopes, constraints, label, expires_at
        FROM app_credentials
       WHERE app_id = $1
      """;

  private final Pool pgPool;

  public AppCredentialsRepository(Pool pgPool) {
    this.pgPool = pgPool;
  }

  /** Fetch credential row by app_id. Returns empty Optional if not found. */
  public Future<Optional<AppCredential>> findByAppId(String appId) {
    return pgPool.preparedQuery(FIND_BY_APP_ID)
        .execute(Tuple.of(java.util.UUID.fromString(appId)))
        .map(rows -> {
          if (rows.rowCount() == 0) return Optional.empty();
          var row = rows.iterator().next();
          return Optional.of(new AppCredential(
              row.getUUID("app_id").toString(),
              row.getUUID("owner_id").toString(),
              row.getString("resource_server_url"),
              row.getString("secret_hash"),
              row.getString("status"),
              List.of(row.getArrayOfStrings("scopes")),
              row.getJsonObject("constraints").encode(),
              row.getString("label"),
              row.getLocalDateTime("expires_at") == null ? null
                  : row.getLocalDateTime("expires_at")
                       .toInstant(java.time.ZoneOffset.UTC)
          ));
        });
  }
}
```

#### 6.2.3 `AppIdVerificationGrpcService.java`

```java
package org.cdpg.dx.auth.appid.grpc;

import io.grpc.stub.StreamObserver;
import org.cdpg.dx.auth.appid.repository.AppCredentialsRepository;
import org.cdpg.dx.auth.appid.v1.*;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * gRPC service implementation.
 * Runs on the Vert.x event-loop; delegate all blocking work (bcrypt) to a worker thread.
 */
public class AppIdVerificationGrpcService
    extends AppIdVerificationServiceGrpc.AppIdVerificationServiceImplBase {

  private static final Logger LOG = LoggerFactory.getLogger(AppIdVerificationGrpcService.class);

  private final AppCredentialsRepository repo;
  private final io.vertx.core.Vertx vertx;

  public AppIdVerificationGrpcService(io.vertx.core.Vertx vertx,
                                       AppCredentialsRepository repo) {
    this.vertx = vertx;
    this.repo  = repo;
  }

  @Override
  public void verifyAppId(VerifyAppIdRequest request,
                          StreamObserver<VerifyAppIdResponse> observer) {

    String appId     = request.getAppId();
    String appSecret = request.getAppSecret();

    repo.findByAppId(appId)
        .onSuccess(optCred -> {
          if (optCred.isEmpty()) {
            // Uniform error — do not reveal "not found" vs "wrong secret"
            observer.onNext(errorResponse("INVALID_CREDENTIALS"));
            observer.onCompleted();
            return;
          }

          var cred = optCred.get();

          // Status check
          if (!"ACTIVE".equals(cred.status())) {
            observer.onNext(errorResponse(cred.status())); // REVOKED or EXPIRED
            observer.onCompleted();
            return;
          }

          // Expiry check
          if (cred.expiresAt() != null && Instant.now().isAfter(cred.expiresAt())) {
            observer.onNext(errorResponse("EXPIRED"));
            observer.onCompleted();
            return;
          }

          // BCrypt verification — blocking, run off event-loop
          vertx.<Boolean>executeBlocking(promise ->
              promise.complete(BCrypt.checkpw(appSecret, cred.secretHash()))
          ).onSuccess(matches -> {
            if (!matches) {
              observer.onNext(errorResponse("INVALID_CREDENTIALS"));
              observer.onCompleted();
              return;
            }

            var principal = AppIdPrincipal.newBuilder()
                .setAppId(cred.appId())
                .setOwnerId(cred.ownerId())
                .setResourceServerUrl(cred.resourceServerUrl())
                .addAllScopes(cred.scopes())
                .setConstraintsJson(cred.constraintsJson())
                .setLabel(cred.label() != null ? cred.label() : "")
                .setExpiresAtEpoch(cred.expiresAt() == null ? 0L
                    : cred.expiresAt().getEpochSecond())
                .build();

            observer.onNext(VerifyAppIdResponse.newBuilder()
                .setSuccess(true)
                .setPrincipal(principal)
                .build());
            observer.onCompleted();
          }).onFailure(err -> {
            LOG.error("BCrypt error for appId={}", appId, err);
            observer.onError(io.grpc.Status.INTERNAL
                .withDescription("Internal verification error")
                .asRuntimeException());
          });
        })
        .onFailure(err -> {
          LOG.error("DB error for appId={}", appId, err);
          observer.onError(io.grpc.Status.INTERNAL
              .withDescription("Database error")
              .asRuntimeException());
        });
  }

  private VerifyAppIdResponse errorResponse(String errorCode) {
    return VerifyAppIdResponse.newBuilder()
        .setSuccess(false)
        .setErrorCode(errorCode)
        .build();
  }
}
```

#### 6.2.4 `GrpcServerVerticle.java`

```java
package org.cdpg.dx.auth.appid.server;

import io.grpc.ServerBuilder;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import org.cdpg.dx.auth.appid.grpc.AppIdVerificationGrpcService;
import org.cdpg.dx.auth.appid.repository.AppCredentialsRepository;

public class GrpcServerVerticle extends AbstractVerticle {

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    var repo    = new AppCredentialsRepository(/* inject Pool */);
    var svc     = new AppIdVerificationGrpcService(vertx, repo);

    int port = config().getInteger("grpcPort", 8090);

    var server = ServerBuilder.forPort(port)
        .addService(svc)
        .build()
        .start();

    vertx.getOrCreateContext().addCloseHook(promise -> {
      server.shutdown();
      promise.complete();
    });

    startPromise.complete();
  }
}
```

Register `GrpcServerVerticle` in controlplane's Deployer alongside existing verticles.

### 6.3 Controlplane Config Addition

```json
{
  "grpcPort": 8090
}
```

---

## 7. Dataplane Changes (dx-dataplane-rs)

### 7.1 New Maven Dependencies

Add to `pom.xml`:

```xml
<!-- gRPC client -->
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
<!-- Proto shared definitions -->
<dependency>
  <groupId>org.cdpg.dx</groupId>
  <artifactId>dx-proto</artifactId>
  <version>1.0.0</version>
</dependency>
```

### 7.2 New Files in Dataplane

```
src/main/java/org/cdpg/dx/auth/appid/
├── handler/
│   └── AppIdAuthHandler.java          ← Vert.x Handler<RoutingContext>
├── cache/
│   └── AppIdCacheService.java         ← Guava cache wrapper
└── client/
    └── AppIdVerificationClient.java   ← gRPC stub wrapper
```

#### 7.2.1 `AppIdCacheService.java`

```java
package org.cdpg.dx.auth.appid.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.cdpg.dx.auth.appid.v1.AppIdPrincipal;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe in-process cache for verified AppId principals.
 * TTL is intentionally short (5 min) so revocations propagate quickly.
 */
public class AppIdCacheService {

  // Key   = appId (UUID string)
  // Value = verified AppIdPrincipal from controlplane
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

> **Cache key is `appId` only.** The secret is **not** used as cache key — the cached entry represents a verified credential. Once the controlplane confirms the secret is correct, we cache the `AppIdPrincipal` associated with that `appId`. Consequence: if the secret is rotated, the old cached entry stays valid until TTL expires (5 min maximum).

> **Why not cache keyed on `appId+secret`?** It would mean hashing the secret on every cache-hit path, gaining nothing. The 5 min TTL already bounds the revocation window acceptably.

#### 7.2.2 `AppIdVerificationClient.java`

```java
package org.cdpg.dx.auth.appid.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.cdpg.dx.auth.appid.v1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-blocking gRPC client.
 * Uses async stub so gRPC callbacks don't block the Vert.x event-loop.
 */
public class AppIdVerificationClient {

  private static final Logger LOG = LoggerFactory.getLogger(AppIdVerificationClient.class);

  private final AppIdVerificationServiceGrpc.AppIdVerificationServiceStub asyncStub;

  public AppIdVerificationClient(String controlplaneHost, int grpcPort) {
    ManagedChannel channel = ManagedChannelBuilder
        .forAddress(controlplaneHost, grpcPort)
        .usePlaintext()           // use .useTransportSecurity() + TLS in production
        .build();
    this.asyncStub = AppIdVerificationServiceGrpc.newStub(channel);
  }

  /**
   * Calls controlplane to verify AppId credentials.
   * Returns a Vert.x Future so callers stay on the event-loop idiom.
   */
  public Future<VerifyAppIdResponse> verify(String appId, String appSecret) {
    Promise<VerifyAppIdResponse> promise = Promise.promise();

    VerifyAppIdRequest req = VerifyAppIdRequest.newBuilder()
        .setAppId(appId)
        .setAppSecret(appSecret)
        .build();

    asyncStub.verifyAppId(req, new StreamObserver<>() {
      @Override public void onNext(VerifyAppIdResponse resp)  { promise.complete(resp); }
      @Override public void onError(Throwable t)              { promise.fail(t); }
      @Override public void onCompleted()                     { /* no-op */ }
    });

    return promise.future();
  }
}
```

#### 7.2.3 `AppIdAuthHandler.java`

```java
package org.cdpg.dx.auth.appid.handler;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import org.cdpg.dx.auth.appid.cache.AppIdCacheService;
import org.cdpg.dx.auth.appid.client.AppIdVerificationClient;
import org.cdpg.dx.auth.appid.v1.AppIdPrincipal;
import org.cdpg.dx.common.model.JwtData;
import org.cdpg.dx.common.util.RsRoutingContextHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Vert.x routing handler that authenticates requests using X-App-Id / X-App-Secret headers.
 *
 * <p>Placement in router chain:
 * <pre>
 *   router.route(...)
 *     .handler(jwtAuthHandler)       // existing JWT auth (skips if no Bearer)
 *     .handler(appIdAuthHandler)     // NEW — handles AppId headers
 *     .handler(AuthorizationHandler.forRoles(...))
 *     ...
 * </pre>
 *
 * <p>If neither JWT nor AppId credentials are present the request fails with 401.
 */
public class AppIdAuthHandler implements Handler<RoutingContext> {

  private static final Logger LOG = LoggerFactory.getLogger(AppIdAuthHandler.class);

  static final String HEADER_APP_ID     = "X-App-Id";
  static final String HEADER_APP_SECRET = "X-App-Secret";

  private final AppIdCacheService          cache;
  private final AppIdVerificationClient    grpcClient;

  public AppIdAuthHandler(AppIdCacheService cache, AppIdVerificationClient grpcClient) {
    this.cache      = cache;
    this.grpcClient = grpcClient;
  }

  @Override
  public void handle(RoutingContext ctx) {

    // If a previous handler (JWT) already authenticated the user, skip.
    if (ctx.user() != null) {
      ctx.next();
      return;
    }

    String appId     = ctx.request().getHeader(HEADER_APP_ID);
    String appSecret = ctx.request().getHeader(HEADER_APP_SECRET);

    if (appId == null || appSecret == null) {
      // No credentials of any kind — reject.
      ctx.fail(401, new IllegalArgumentException("Missing authentication credentials"));
      return;
    }

    // Input sanity: UUID format for appId
    if (!isValidUuid(appId)) {
      ctx.fail(400, new IllegalArgumentException("Invalid X-App-Id format"));
      return;
    }

    // Check cache first
    var cached = cache.get(appId);
    if (cached.isPresent()) {
      LOG.debug("AppId cache HIT for appId={}", appId);
      populateContext(ctx, cached.get());
      ctx.next();
      return;
    }

    // Cache MISS → verify via gRPC
    LOG.debug("AppId cache MISS for appId={} — calling controlplane", appId);
    grpcClient.verify(appId, appSecret)
        .onSuccess(response -> {
          if (!response.getSuccess()) {
            String code = response.getErrorCode();
            LOG.warn("AppId verification failed: appId={} reason={}", appId, code);
            int httpStatus = switch (code) {
              case "REVOKED"  -> 403;
              case "EXPIRED"  -> 403;
              default         -> 401;   // INVALID_CREDENTIALS or unknown
            };
            ctx.fail(httpStatus,
                new SecurityException("AppId authentication failed: " + code));
            return;
          }

          var principal = response.getPrincipal();
          // Cache the verified principal (keyed by appId only — see §7.2.1)
          cache.put(appId, principal);
          populateContext(ctx, principal);
          ctx.next();
        })
        .onFailure(err -> {
          LOG.error("gRPC call failed during AppId verification", err);
          ctx.fail(503, new RuntimeException("Authentication service unavailable"));
        });
  }

  // ──────────────────────────────────────────────────────────────
  // Context population
  // ──────────────────────────────────────────────────────────────

  /**
   * Converts an {@link AppIdPrincipal} into the same {@link JwtData} shape that
   * every downstream handler already knows how to read.
   *
   * <p>Mapping table:
   * <pre>
   *   AppIdPrincipal.ownerId           → JwtData.sub
   *   AppIdPrincipal.resourceServerUrl → JwtData.aud  (closest semantic match)
   *   AppIdPrincipal.scopes            → JwtData.iid  (space-joined)
   *   AppIdPrincipal.constraintsJson   → JwtData.cons
   *   "appid"                          → JwtData.role  (new role sentinel)
   *   AppIdPrincipal.expiresAtEpoch    → JwtData.exp
   * </pre>
   */
  private void populateContext(RoutingContext ctx, AppIdPrincipal principal) {

    var cons = io.vertx.core.json.JsonObject.mapFrom(
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValueUnchecked(principal.getConstraintsJson(),
                java.util.Map.class));

    JwtData jwtData = new JwtData(
        /* accessToken */ null,                                   // no JWT
        /* sub         */ principal.getOwnerId(),
        /* iss         */ "appid",                               // sentinel issuer
        /* aud         */ principal.getResourceServerUrl(),
        /* exp         */ principal.getExpiresAtEpoch() == 0
                              ? null : principal.getExpiresAtEpoch(),
        /* iat         */ System.currentTimeMillis() / 1000L,
        /* iid         */ String.join(" ", principal.getScopesList()),
        /* role        */ "appid",                               // new role sentinel
        /* cons        */ cons,
        /* drl         */ null,
        /* did         */ null,
        /* expiry      */ null
    );

    RsRoutingContextHelper.setJwtData(ctx, jwtData);

    // Also set a Vert.x User principal so AuthorizationHandler.forRoles can inspect it.
    // We use a lightweight JsonObject principal identical to what JWT auth sets.
    var principalJson = new io.vertx.core.json.JsonObject()
        .put("sub",   principal.getOwnerId())
        .put("role",  "appid")
        .put("scopes", new io.vertx.core.json.JsonArray(principal.getScopesList()))
        .put("iss",   "appid");

    ctx.setUser(io.vertx.ext.auth.User.fromName(principal.getOwnerId())
        .attributes(principalJson));
  }

  private static boolean isValidUuid(String s) {
    try { java.util.UUID.fromString(s); return true; }
    catch (IllegalArgumentException e) { return false; }
  }
}
```

### 7.3 Registering the Handler in the Router

**File:** `src/main/java/org/cdpg/dx/apiserver/ApiServerVerticle.java`

Locate the section where handler chains are set up (currently done in controller constructors). Add the AppId handler **after** JWT auth and **before** `AuthorizationHandler.forRoles(...)`.

```java
// In ApiServerVerticle.start() or wherever controllers are wired:

AppIdCacheService         appIdCache  = new AppIdCacheService(5000, 5);
AppIdVerificationClient   appIdClient = new AppIdVerificationClient(
    config.getString("controlplaneHost"),
    config.getInteger("controlplaneGrpcPort", 8090)
);
AppIdAuthHandler appIdAuthHandler = new AppIdAuthHandler(appIdCache, appIdClient);

// Then pass appIdAuthHandler into each controller constructor,
// or register it globally on the router:
router.route().handler(appIdAuthHandler);
// (If placed globally, it checks ctx.user() != null first and fast-paths — see §7.2.3)
```

### 7.4 Changes to `AuthorizationHandler` (dx-auth library)

The external `AuthorizationHandler.forRoles(DxRole.CONSUMER, DxRole.DELEGATE)` currently only knows about JWT roles. Two options:

**Option A (preferred) — add `DxRole.APP_ID` to the dx-auth library:**

```java
// In dx-auth: DxRole enum
public enum DxRole {
  PROVIDER, CONSUMER, DELEGATE, ADMIN,
  APP_ID   // new — machine client
}
```

Modify `AuthorizationHandler.forRoles(...)` to pass through when `role == "appid"` and at least one of the `forRoles` set is `DxRole.CONSUMER` (AppId credentials act as consumer-level by default):

```java
// Pseudocode within AuthorizationHandler
if ("appid".equals(principalRole) && allowedRoles.contains(DxRole.APP_ID)) {
  context.next();
  return;
}
```

**Option B — bypass for AppId in ResourcePolicyAuthorizationHandler:**

If dx-auth cannot be changed quickly, add an early-exit in `ResourcePolicyAuthorizationHandler`:

```java
// ResourcePolicyAuthorizationHandler.java  (already in this repo)
var jwtDataOpt = RsRoutingContextHelper.getJwtData(routingContext);
if (jwtDataOpt.isPresent() && "appid".equals(jwtDataOpt.get().role())) {
  // AppId credentials bypass JWT-specific checks; resource policy check still runs.
  // Continue to resource-level access check.
}
```

### 7.5 Changes to `ResourcePolicyAuthorizationHandler`

**File:** `src/main/java/org/cdpg/dx/rs/authorization/handler/ResourcePolicyAuthorizationHandler.java`

The existing handler calls `AuthorizationServiceImpl` which checks `jwtData.iid()` against the resource ID for SECURE resources. For AppId credentials the `iid` field contains the granted scopes (not a resource item ID). Update `AuthorizationServiceImpl`:

```java
// AuthorizationServiceImpl.java
// Existing check (simplified):
//   if SECURE: validate jwtData.iid().split(":")[0].equals(resourceId)

// New check:
if ("appid".equals(jwtData.role())) {
  // AppId scope check: scopes list must contain "read" (or whatever is required)
  boolean hasReadScope = Arrays.asList(jwtData.iid().split(" "))
      .contains("read");
  if (!hasReadScope) {
    throw new DxForbiddenNoAccessException("AppId credential lacks required scope");
  }
  // Resource-level constraint check (from jwtData.cons) still applies
} else {
  // existing iid-based check for JWT tokens
}
```

### 7.6 Dataplane Config Addition

```json
{
  "commonConfig": {
    "controlplaneHost":     "controlplane.iudx.io",
    "controlplaneGrpcPort": 8090,
    "appIdCache": {
      "maxSize":    5000,
      "ttlMinutes": 5
    }
  }
}
```

---

## 8. JwtData Mapping Reference

| AppIdPrincipal field    | JwtData field | Notes                                       |
|-------------------------|---------------|---------------------------------------------|
| `ownerId`               | `sub`         | User/service owner UUID                     |
| `"appid"` (literal)     | `iss`         | Sentinel to identify AppId auth path        |
| `"appid"` (literal)     | `role`        | Consumed by AuthorizationHandler            |
| `resourceServerUrl`     | `aud`         | Closest semantic match                      |
| `scopes` (space-joined) | `iid`         | Read by AuthorizationServiceImpl            |
| `constraintsJson`       | `cons`        | Passed as-is to resource access checks      |
| `expiresAtEpoch`        | `exp`         | 0 → null (no expiry)                        |
| `null`                  | `accessToken` | No JWT; handlers must not dereference this  |

---

## 9. Security Considerations

| Risk                              | Mitigation                                                                                       |
|-----------------------------------|--------------------------------------------------------------------------------------------------|
| Secret exposure in logs           | Never log `X-App-Secret`; log only `appId`                                                       |
| Brute-force secret guessing       | bcrypt work factor ≥ 12; rate-limit 401s per `appId` (Redis `INCR` + TTL in rate-limit handler) |
| Cache poisoning after revocation  | 5-min TTL; provide admin endpoint `DELETE /internal/appid-cache/{appId}` for immediate eviction  |
| Timing attacks on secret compare  | bcrypt's constant-time comparison is intrinsic                                                   |
| Man-in-the-middle on gRPC         | Use mTLS for the internal gRPC channel in production; allow plaintext only in dev                |
| Secret sent over plain HTTP       | Enforce HTTPS at the load balancer; return 403 on non-TLS                                        |
| AppId header injection from JWT users | `AppIdAuthHandler` skips when `ctx.user() != null` — JWT always takes precedence           |

---

## 10. Failure Modes & Error Responses

| Scenario                          | HTTP Status | Body (`type` field)      |
|-----------------------------------|-------------|--------------------------|
| Headers missing entirely          | 401         | `NotAuthorized`          |
| Malformed UUID in X-App-Id        | 400         | `BadRequest`             |
| Credentials invalid (wrong secret)| 401         | `NotAuthorized`          |
| Credential REVOKED                | 403         | `Forbidden`              |
| Credential EXPIRED                | 403         | `Forbidden`              |
| gRPC call to controlplane fails   | 503         | `ServiceUnavailable`     |
| AppId lacks required scope        | 403         | `Forbidden`              |

---

## 11. Testing Plan

### 11.1 Unit Tests (Dataplane)

| Class                        | Test cases                                                                                                    |
|------------------------------|---------------------------------------------------------------------------------------------------------------|
| `AppIdCacheService`          | `get` returns empty on miss; `put` then `get` returns value; entry expires after TTL                         |
| `AppIdAuthHandler`           | Skips when `ctx.user()` set; fails 401 on missing headers; fails 400 on bad UUID; cache hit calls `next()`; cache miss calls gRPC; maps gRPC error codes to correct HTTP status |
| `AppIdVerificationClient`    | Returns completed future on `success=true`; fails future on gRPC error                                       |

### 11.2 Unit Tests (Controlplane)

| Class                              | Test cases                                                                                           |
|------------------------------------|------------------------------------------------------------------------------------------------------|
| `AppCredentialsRepository`         | Returns empty on unknown appId; returns credential on valid appId                                   |
| `AppIdVerificationGrpcService`     | Returns `INVALID_CREDENTIALS` on unknown appId; returns `REVOKED` on revoked credential; returns `EXPIRED` when past `expires_at`; returns success + principal on valid credentials |

### 11.3 Integration Tests

1. Full flow: client sends `X-App-Id` + `X-App-Secret` → dataplane → controlplane (real DB) → 200 response
2. Cache hit path: second request with same AppId uses cache, no gRPC call
3. Revocation propagation: revoke credential in DB → requests within 5-min window still succeed (expected) → after TTL, requests fail 403
4. JWT + AppId coexistence: request with `Authorization: Bearer <jwt>` ignores AppId headers

---

## 12. Rollout & Feature Flag

Add a config flag to allow gradual rollout:

```json
{
  "featureFlags": {
    "appIdAuthEnabled": true
  }
}
```

In `AppIdAuthHandler.handle()`:

```java
if (!config.getBoolean("featureFlags.appIdAuthEnabled", false)) {
  ctx.next();   // pass-through; behaves as if handler is not installed
  return;
}
```

Start with `appIdAuthEnabled: false` in production, enable per environment after integration tests pass.

---

## 13. Summary of All Changed/New Files

### Controlplane (dx-controlplane)

| File                                                              | Change Type |
|-------------------------------------------------------------------|-------------|
| `pom.xml`                                                         | Modified    |
| `src/main/resources/db/migration/V<n>__add_app_credentials.sql`  | New         |
| `src/main/java/.../auth/appid/model/AppCredential.java`          | New         |
| `src/main/java/.../auth/appid/repository/AppCredentialsRepository.java` | New  |
| `src/main/java/.../auth/appid/grpc/AppIdVerificationGrpcService.java`   | New  |
| `src/main/java/.../auth/appid/server/GrpcServerVerticle.java`    | New         |
| `src/main/java/.../deploy/Deployer.java`                         | Modified    |
| `configs/config-dev.json`                                        | Modified    |

### Dataplane (dx-dataplane-rs)

| File                                                              | Change Type |
|-------------------------------------------------------------------|-------------|
| `pom.xml`                                                         | Modified    |
| `src/main/java/.../auth/appid/cache/AppIdCacheService.java`      | New         |
| `src/main/java/.../auth/appid/client/AppIdVerificationClient.java`| New        |
| `src/main/java/.../auth/appid/handler/AppIdAuthHandler.java`     | New         |
| `src/main/java/.../apiserver/ApiServerVerticle.java`             | Modified    |
| `src/main/java/.../rs/authorization/service/AuthorizationServiceImpl.java` | Modified |
| `src/main/java/.../rs/authorization/handler/ResourcePolicyAuthorizationHandler.java` | Modified (minor) |
| `configs/config-dev.json`                                        | Modified    |

### Shared (dx-proto — new repo)

| File                                             | Change Type |
|--------------------------------------------------|-------------|
| `proto/appid_verification.proto`                 | New         |
| `pom.xml`                                        | New         |

### External Library (dx-auth)

| File                        | Change Type |
|-----------------------------|-------------|
| `DxRole.java`               | Modified — add `APP_ID` value |
| `AuthorizationHandler.java` | Modified — handle `APP_ID` role |
