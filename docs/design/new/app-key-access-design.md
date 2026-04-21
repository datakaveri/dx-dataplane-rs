# App Key Access Design — AppId + JWT Dual Auth

**Version:** 2.0  
**Date:** 2026-04-06  
**Author:** Ankit Singh  
**Status:** Draft — Awaiting Manager Approval

---

## 1. Overview

This document designs the **App Key Access** mechanism for the DX platform. A registered user gets an `appId` (a UUID) from the DX portal and uses it as a bearer credential to call data APIs directly — **no JWT, no Keycloak login required**.

JWT authentication remains fully supported in parallel. Both paths converge at the same downstream RoutingContext state.

### 1.1 Why AppId?

The existing JWT flow requires users to log in via Keycloak, receive a short-lived token, and refresh it repeatedly. This is painful for:

- Scripts running overnight pulling sensor data
- IoT devices that cannot do interactive login
- Researchers and developers who just want a stable API credential

AppId solves this:

```
JWT flow today:
  User → login Keycloak → get JWT (expires 1hr) → refresh → repeat forever

AppId flow:
  User → register on DX portal → get appId → use it forever (until revoked)
```

### 1.2 Who Are the Users?

Your company builds the full stack — DX portal (frontend), dataplane, and controlplane. The users are external parties who register on the portal:

- Researchers querying air quality / traffic sensor data
- Smart city teams pulling real-time telemetry
- IoT companies integrating with DX APIs

They do not write any platform code. They register, receive an appId, and call your NGSILD Search API directly.

### 1.3 Dual Auth Contract

```
Option A: Authorization: Bearer <JWT>   → existing JWT flow (unchanged)
Option B: X-App-Id: <appId>             → new app key flow (this design)
```

Both flows must converge to the same RoutingContext state before business logic handlers execute.

---

## 2. Security Model

### 2.1 The AppId IS the Credential

The appId is a UUID v4 — a random 122-bit value. This is the same model used by Stripe, Twilio, GitHub, and SendGrid for their API keys.

```
appId = "550e8400-e29b-41d4-a716-446655440000"

Randomness: 122 bits
Possible values: 5,316,911,983,139,663,491,615,228,241,121,400,000
Brute force: computationally impossible
```

**No separate password or secret is stored or compared.** The DB simply stores the appId UUID alongside the user's permissions. Verification is a DB lookup — not a crypto operation.

### 2.2 What Protects Against Attackers

| Threat | Protection |
|---|---|
| Intercept appId in transit | HTTPS — all traffic encrypted, appId never visible on wire |
| Guess a valid appId | UUID randomness — 122 bits, brute force impossible |
| Enumerate appIds | Rate limiting — 10 failed attempts = IP block |
| Stolen appId | Instant revocation — admin revokes in DB, pushed to dataplane via gRPC stream within seconds |
| Replay attack | HTTPS prevents interception; revocation handles stolen credentials |

### 2.3 Why No Password Hash Is Needed

Traditional password auth:
```
Store: SHA256(password)
Verify: SHA256(received) == stored?  → needs hash comparison
```

AppId auth:
```
Store: appId (plain UUID in DB)
Verify: SELECT WHERE app_id = ? AND status = ACTIVE  → found = valid
```

There is no secret to hash. The UUID itself is the credential. This is identical to how every major API platform works — the API key IS the secret, stored as-is, protected by HTTPS and revocability.

---

## 3. Alternate Approach Considered — SHA + Timestamp Signature

> **Status: Not adopted for Phase 1. Kept here as a design record with an open question that needs resolution before this can be reconsidered.**

### 3.1 What This Approach Proposes

Instead of sending the raw appId on every request, the user sends a **SHA256 signature** computed from the appId and the current timestamp. The appId acts only as an identifier — the signature proves possession without exposing the secret on the wire.

**Every API request the user makes:**

```
X-App-Id:    550e8400-e29b-41d4-a716-446655440000   ← identifies who (public)
X-Timestamp: 1743552042                              ← current unix epoch
X-Signature: SHA256(secret + "|" + timestamp)        ← proves they hold the secret
```

The raw secret **never travels on the wire**. Only the hash does.

**Controlplane verification logic:**

```
1. Receive appId, timestamp, signature
2. Timestamp freshness check: |now - timestamp| <= 30 seconds → reject if stale
3. Fetch stored secret by appId
4. Recompute: expected = SHA256(storedSecret + "|" + receivedTimestamp)
5. Constant-time compare: expected == receivedSignature?
6. Both pass → genuine request
```

**Replay attack prevention:**

```
Attacker captures at T=1743552042:
  X-Signature: d4e5f6a7...  (valid for this timestamp only)

Attacker replays at T=1743552180 (2 min later):
  |1743552180 - 1743552042| = 138 seconds > 30s limit
  REJECTED — timestamp too old

Attacker tries to forge for a new timestamp:
  Needs SHA256(secret + "|" + newTimestamp)
  Cannot — does not have the raw secret
  REJECTED — cannot forge
```

### 3.2 Why It Is Stronger Than Plain AppId

| Property | Plain UUID AppId | SHA + Timestamp |
|---|---|---|
| Secret on wire | Yes — appId IS the secret | No — only SHA hash travels |
| If appId/secret stolen from wire | Attacker has permanent credential | Attacker has a hash valid for ≤30s only |
| Replay window | Indefinite until revoked | Maximum 30 seconds |
| DB breach | All appIds exposed | Only hashes exposed (if stored as hash) |
| Client complexity | Send one header | Compute SHA256 — available in every language |

### 3.3 ❓ Open Question — The Storage Problem

**This is the unresolved blocker that prevents adopting this approach today.**

To verify the signature, the controlplane must run:

```
SHA256(storedSecret + "|" + timestamp)
```

This requires the **raw secret**. You cannot store just a hash of the secret and use it to recompute the signature — a hash is one-way and cannot be reversed.

So the question becomes: **how does the controlplane store the secret?**

| Storage Option | Problem |
|---|---|
| Store raw secret in DB (plain text) | DB breach exposes all secrets — worse than plain appId |
| Store SHA256(secret) in DB | Cannot recompute signature — one-way hash, unusable for verification |
| Store AES-256 encrypted secret | Need to manage a master encryption key securely — where does the key live? Who rotates it? |
| Use a secrets vault (HashiCorp Vault, AWS Secrets Manager) | 3rd party dependency — adds infra complexity |

**The same storage problem exists for TOTP** — the seed must be stored in raw or encrypted form for OTP recomputation. Neither TOTP nor SHA+Timestamp escapes this.

> **Open Question for team / manager:**
>
> If we want the security benefit of SHA+Timestamp (secret never on wire, 30s replay window), we must answer:
>
> - Do we accept AES-256 encrypted storage of secrets in the DB with a master key in environment variables? (simplest — no new infra, master key managed via deployment secrets)
> - Or do we stay with plain UUID appId for Phase 1 (no storage problem, industry-proven) and revisit SHA+Timestamp in Phase 2 once the key management story is clear?
>
> **Current assumption:** Plain UUID for Phase 1. If the team decides AES-encrypted storage is acceptable, SHA+Timestamp can be adopted in Phase 2 with the implementation below.

### 3.4 How It Would Be Implemented (If Adopted)

**Registration — generate appId + separate secret:**

```java
// Controlplane — AppRegistrationService
public AppRegistrationResponse register(String userId) {
    String appId  = UUID.randomUUID().toString();  // public identifier
    String secret = UUID.randomUUID().toString();  // the actual secret — never stored raw

    // AES-256 encrypt the secret with master key from env
    String encryptedSecret = aesEncrypt(secret, masterKey);

    appCredentialRepository.save(new AppCredential(
        appId,
        userId,
        encryptedSecret,   // stored encrypted — raw secret is NOT in DB
        AppStatus.ACTIVE
    ));

    // Return both to user — shown ONCE on portal
    return new AppRegistrationResponse(appId, secret);
}
```

**Controlplane verification:**

```java
public Future<AppVerifyResponse> verify(String appId, String timestamp, String signature) {

    // 1. Timestamp freshness — fail fast, no DB hit needed
    long receivedTs = Long.parseLong(timestamp);
    long now = Instant.now().getEpochSecond();
    if (Math.abs(now - receivedTs) > 30) {
        return Future.failedFuture(new DxUnauthorizedException("Request timestamp expired"));
    }

    return appCredentialRepository.findByAppId(appId)
        .compose(cred -> {
            if (cred == null || cred.getStatus() != AppStatus.ACTIVE) {
                return Future.failedFuture(new DxUnauthorizedException("AppId not found or inactive"));
            }

            // 2. Decrypt secret using master key from env variable
            String rawSecret = aesDecrypt(cred.getEncryptedSecret(), masterKey);

            // 3. Recompute expected signature
            String expected = sha256(rawSecret + "|" + timestamp);

            // 4. Constant-time compare — prevents timing attacks
            if (!MessageDigest.isEqual(expected.getBytes(), signature.getBytes())) {
                return Future.failedFuture(new DxUnauthorizedException("Signature mismatch"));
            }

            // 5. All checks passed — fetch constraints and return
            return appConstraintRepository.findByAppId(appId)
                .map(constraints -> buildResponse(cred, constraints));
        });
}

private String sha256(String input) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
    }
}
```

**Updated AppKeyAuthHandler (dataplane) — extract all three headers:**

```java
public void handle(RoutingContext ctx) {
    String appId     = ctx.request().getHeader("X-App-Id");
    String timestamp = ctx.request().getHeader("X-Timestamp");
    String signature = ctx.request().getHeader("X-Signature");

    if (appId == null || appId.isBlank()) {
        ctx.next();  // fall through to JWT handler
        return;
    }

    // Both timestamp and signature are required with appId
    if (timestamp == null || signature == null) {
        ctx.fail(new DxUnauthorizedException(
            "X-Timestamp and X-Signature are required alongside X-App-Id"));
        return;
    }

    // Dataplane does NOT verify the signature — it does not have secrets
    // It forwards all three values to controlplane via gRPC
    client.verify(appId, timestamp, signature)
        .onSuccess(authCtx -> {
            RsRoutingContextHelper.setAppAuthContext(ctx, authCtx);
            ctx.next();
        })
        .onFailure(err -> ctx.fail(new DxUnauthorizedException("Signature verification failed")));
}
```

**Updated proto (if SHA+Timestamp adopted):**

```protobuf
message VerifyAppRequest {
    string app_id    = 1;
    string timestamp = 2;   // unix epoch string — added for SHA+Timestamp
    string signature = 3;   // SHA256(secret + "|" + timestamp) — added for SHA+Timestamp
    string requesting_service = 4;
}
```

**User-side signature computation (any language):**

```python
# Python example — standard library only, no SDK needed
import hashlib, time

app_id  = "550e8400-e29b-41d4-a716-446655440000"
secret  = "your-secret-from-portal"
ts      = str(int(time.time()))
sig     = hashlib.sha256(f"{secret}|{ts}".encode()).hexdigest()

headers = {
    "X-App-Id":    app_id,
    "X-Timestamp": ts,
    "X-Signature": sig
}
```

```javascript
// Node.js example
const crypto = require('crypto');
const ts  = Math.floor(Date.now() / 1000).toString();
const sig = crypto.createHash('sha256')
                  .update(`${secret}|${ts}`)
                  .digest('hex');
```

### 3.5 Summary

| Item | Detail |
|---|---|
| Security benefit over plain UUID | Secret never on wire; replay window max 30 seconds |
| Blocker | Must decide how to store the secret on controlplane side |
| Recommended resolution | AES-256 encrypt with master key in env variable — no new infra, standard practice |
| When to revisit | Phase 2, after Phase 1 (plain UUID) is stable and team agrees on key management |

---

## 4. Current Architecture (Baseline)

### 3.1 Dataplane Handler Chain (Current)

```
HTTP Request
    │
    ▼
[JwtAuthHandler]                      ← validates JWT, populates JwtData in RoutingContext
    │
    ▼
[ResourcePolicyAuthorizationHandler]  ← reads JwtData, calls CatalogueService
    │
    ▼
[CheckItemAccessHandler]              ← calls controlplane /iudx/acl/apd/v2/access_request/has_access
    │                                    passes user's Bearer JWT to controlplane
    ▼
[ItemAccessApplicableFilterHandlerNgsild]
    │
    ▼
[ProviderValidationHandler / ProviderDelegateValidationHandler]
    │
    ▼
[Controller (Latest / Download / NGSILD / Publish)]
    │
    ▼
[AuditingHandler]
```

### 3.2 Key Observation

`CheckItemAccessHandler` forwards the **user's JWT** directly to the controlplane. For appId auth, there is no JWT to forward. A new verification pathway is needed — the dataplane must ask the controlplane "is this appId valid?" via a separate internal call.

---

## 5. Chosen Approach — Plain AppId via gRPC

### 4.1 Why This Approach

After evaluating 8 approaches (HTTPS REST, gRPC, event-driven cache, signed JWT token, SHA+timestamp, full request signing, TOTP, API gateway), the chosen design is:

**Plain UUID appId verified via gRPC from dataplane to controlplane.**

Reasons:
- No new secrets or crypto — the UUID is the credential, same as every major API platform
- No password/hash storage problem — just a DB lookup
- gRPC gives a **persistent connection** between dataplane and controlplane — fast binary verification on every call, and real-time revocation push (controlplane pushes "appId revoked" events directly to dataplane over the same connection, no polling, no RabbitMQ needed for this)
- Dataplane stays **stateless for auth** — it holds a short-lived cache but owns no credential data
- Everything else (TOTP, SHA signatures) introduced complexity without solving a real threat that HTTPS + UUID randomness + instant revocation doesn't already cover

### 4.2 Why Not TOTP or SHA+Timestamp

**TOTP** requires storing the seed on the controlplane side. You cannot store just a hash of the seed — you need the raw seed to recompute the OTP. This means either storing it encrypted (key management complexity) or using a vault (3rd party dependency). This complexity is not justified when HTTPS already protects the appId in transit.

**SHA+Timestamp** has the same problem — to verify `SHA256(secret + timestamp)`, the controlplane needs the raw secret. You end up needing to store an encrypted secret or use a vault. Same problem as TOTP, less benefit.

**Plain UUID over HTTPS** sidesteps all of this. No secret to store. No crypto on the verification path. The UUID's 122-bit randomness makes it unguessable, and HTTPS makes it uninterceptable.

### 4.3 Why gRPC Specifically

```
HTTP REST (Approach 1):
  Dataplane → new HTTP connection → POST /app/verify → controlplane
  → JSON serialization, slower
  → no built-in streaming
  → revocation needs separate RabbitMQ consumer

gRPC (Approach 2):
  Dataplane ←→ controlplane: ONE persistent HTTP/2 connection, always open
  → binary Protobuf, faster
  → VerifyApp() RPC: fast unary call on existing connection
  → WatchRevocations() RPC: controlplane PUSHES revocation events to dataplane
  → no polling, no separate RabbitMQ needed for revocation
```

The persistent gRPC connection handles both verification AND revocation over a single channel. The dataplane never needs to poll or maintain a separate message consumer for auth events.

---

## 6. Architecture

### 5.1 Registration Flow (One-Time per User)

```
User registers on DX Portal
        │
        ▼
Controlplane generates:
  appId = UUID.randomUUID()  → "550e8400-e29b-41d4-a716-446655440000"

Stores in DB:
  app_credentials:
    app_id     = "550e8400-..."   ← plain UUID, this IS the credential
    user_id    = "user-uuid"
    status     = ACTIVE
    created_at = now()
    expires_at = null (or configured expiry)

  app_constraints:
    app_id         = "550e8400-..."
    resource_id    = "urn:dx:rs:domain/rs/group/item-1"
    access_policy  = "SECURE"
    filters        = { "time": "P30D" }
    attributes     = ["temperature", "humidity"]

Portal shows user:
  appId = "550e8400-e29b-41d4-a716-446655440000"
  ← shown once, user copies it, no seed, no secret, just this UUID
```

### 5.2 API Request Flow

```
User's script / app:
  GET /iudx/v2/entities?id=urn:dx:rs:domain/rs/group/item-1
  X-App-Id: 550e8400-e29b-41d4-a716-446655440000
  (over HTTPS — appId encrypted in transit)
        │
        ▼
DATAPLANE — AppKeyAuthHandler
  → extracts X-App-Id header
  → checks local Caffeine cache (key = appId)
  → cache hit? → use cached AppAuthContext → ctx.next()
  → cache miss? → call controlplane via gRPC ──────────────────►
                                                CONTROLPLANE
                                                AppVerifyServiceImpl
                                                  SELECT * FROM app_credentials
                                                  WHERE app_id = ? AND status = ACTIVE
                                                  → found → fetch app_constraints
                                                  → build AppVerifyResponse
                                               ◄──────────────────
  → parse response into AppAuthContext
  → cache it (TTL 5 min)
  → RsRoutingContextHelper.setAppAuthContext(ctx, appAuthContext)
  → ctx.next()
        │
        ▼
[ResourcePolicyAuthorizationHandler] — checks appCtx.covers(resourceId)
        │
        ▼
[CheckItemAccessHandler] — skipped for AppId path
        │
        ▼
[ItemAccessApplicableFilterHandlerNgsild] — reads filters from AppAuthContext
        │
        ▼
[Controller] — serves data
        │
        ▼
[AuditingHandler] — logs authMethod=APP_KEY, masked appId
```

### 5.3 Revocation Flow

```
Admin revokes appId in controlplane UI
        │
        ▼
AppCredentialRepository.revoke(appId)
  → UPDATE app_credentials SET status = REVOKED WHERE app_id = ?
        │
        ▼
gRPC WatchRevocations stream (always open, pushed to all dataplane instances)
  → RevocationEvent { appId: "550e8400-...", reason: "ADMIN_REVOKED" }
        │
        ▼
Dataplane AppRevocationStreamHandler
  → cachingClient.invalidate("550e8400-...")
        │
        ▼
Next request with this appId:
  → cache miss → gRPC VerifyApp() → controlplane returns REVOKED → 401
  (within seconds of admin action)
```

---

## 7. Proto Definition

```protobuf
// app_verify.proto
syntax = "proto3";
package dx.auth.v1;

option java_package = "org.cdpg.dx.auth.grpc";
option java_outer_classname = "AppVerifyProto";

service AppVerifyService {
    // Unary: dataplane asks "is this appId valid?"
    rpc VerifyApp(VerifyAppRequest) returns (VerifyAppResponse);

    // Server-streaming: controlplane pushes revocation events to dataplane
    rpc WatchRevocations(WatchRevocationsRequest) returns (stream RevocationEvent);
}

message VerifyAppRequest {
    string app_id = 1;
    string requesting_service = 2;  // "dx-dataplane-rs" for audit
}

message VerifyAppResponse {
    bool valid = 1;
    string user_id = 2;
    string role = 3;
    repeated AccessibleResource accessible_resources = 4;
    int64 expires_at_epoch = 5;
    string detail = 6;  // error message on failure
}

message AccessibleResource {
    string resource_id = 1;
    string access_policy = 2;
    repeated string applicable_filters = 3;
    repeated string allowed_attributes = 4;
}

message WatchRevocationsRequest {
    string service_id = 1;  // identifies which dataplane instance
}

message RevocationEvent {
    string app_id = 1;
    string reason = 2;  // "REVOKED" | "EXPIRED" | "CONSTRAINT_CHANGED"
    int64 timestamp = 3;
}
```

---

## 8. Dataplane Implementation

### 7.1 New Classes

#### `AppAuthContext.java`
```java
public class AppAuthContext {
    private final String appId;
    private final String userId;
    private final String role;
    private final List<AccessibleResource> accessibleResources;
    private final Instant expiry;

    public boolean covers(String resourceId) {
        return accessibleResources.stream()
            .anyMatch(r -> r.getResourceId().equals(resourceId));
    }

    public Optional<AccessibleResource> getResource(String resourceId) {
        return accessibleResources.stream()
            .filter(r -> r.getResourceId().equals(resourceId))
            .findFirst();
    }
}
```

#### `AccessibleResource.java`
```java
public class AccessibleResource {
    private final String resourceId;
    private final String accessPolicy;       // OPEN / SECURE / PII
    private final List<String> applicableFilters;
    private final List<String> allowedAttributes;
}
```

#### `AppCredentialClient.java` (interface)
```java
public interface AppCredentialClient {
    Future<AppAuthContext> verify(String appId);
    void invalidate(String appId);
}
```

#### `AppCredentialGrpcClient.java`
```java
public class AppCredentialGrpcClient implements AppCredentialClient {
    private final GrpcClient grpcClient;
    private final SocketAddress controlplaneAddress;

    public Future<AppAuthContext> verify(String appId) {
        VerifyAppRequest req = VerifyAppRequest.newBuilder()
            .setAppId(appId)
            .setRequestingService("dx-dataplane-rs")
            .build();

        return grpcClient
            .request(controlplaneAddress, AppVerifyServiceGrpc.getVerifyAppMethod())
            .compose(grpcReq -> {
                grpcReq.end(req);
                return grpcReq.response().compose(resp -> resp.last());
            })
            .map(this::toAppAuthContext);
    }

    // Subscribe to revocation stream — called once at startup
    public void subscribeToRevocations(CachingAppCredentialClient cache) {
        WatchRevocationsRequest req = WatchRevocationsRequest.newBuilder()
            .setServiceId("dx-dataplane-rs-" + instanceId)
            .build();

        grpcClient
            .request(controlplaneAddress, AppVerifyServiceGrpc.getWatchRevocationsMethod())
            .compose(r -> { r.end(req); return r.response(); })
            .onSuccess(resp -> {
                resp.handler(event -> {
                    LOGGER.info("Revocation received for appId prefix={}",
                        event.getAppId().substring(0, 8));
                    cache.invalidate(event.getAppId());
                });
            });
    }

    public void invalidate(String appId) {}  // no-op; revocation handled via stream
}
```

#### `CachingAppCredentialClient.java`
```java
public class CachingAppCredentialClient implements AppCredentialClient {
    private final Cache<String, AppAuthContext> cache = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build();
    private final AppCredentialClient upstream;

    public Future<AppAuthContext> verify(String appId) {
        AppAuthContext hit = cache.getIfPresent(appId);
        if (hit != null && !hit.getExpiry().isBefore(Instant.now())) {
            return Future.succeededFuture(hit);
        }
        return upstream.verify(appId).map(ctx -> {
            cache.put(appId, ctx);
            return ctx;
        });
    }

    public void invalidate(String appId) {
        cache.invalidate(appId);
        LOGGER.info("Cache invalidated for appId prefix={}", appId.substring(0, 8));
    }
}
```

#### `AppKeyAuthHandler.java`
```java
public class AppKeyAuthHandler implements Handler<RoutingContext> {
    private static final String APP_ID_HEADER = "X-App-Id";
    private final AppCredentialClient client;

    public void handle(RoutingContext ctx) {
        String appId = ctx.request().getHeader(APP_ID_HEADER);
        if (appId == null || appId.isBlank()) {
            // Not an app-key request — fall through to JWT handler
            ctx.next();
            return;
        }

        client.verify(appId)
            .onSuccess(authCtx -> {
                RsRoutingContextHelper.setAppAuthContext(ctx, authCtx);
                ctx.next();
            })
            .onFailure(err -> {
                LOGGER.error("AppId verification failed: {}", err.getMessage());
                ctx.fail(new DxUnauthorizedException("Invalid or revoked appId"));
            });
    }
}
```

### 7.2 Modified Classes

#### `RsRoutingContextHelper.java` — Add AppAuthContext support
```java
public static final String APP_AUTH_CONTEXT_KEY = "appAuthContext";

public static void setAppAuthContext(RoutingContext ctx, AppAuthContext context) {
    ctx.data().put(APP_AUTH_CONTEXT_KEY, context);
}

public static Optional<AppAuthContext> getAppAuthContext(RoutingContext ctx) {
    return Optional.ofNullable((AppAuthContext) ctx.data().get(APP_AUTH_CONTEXT_KEY));
}

public static boolean isAppKeyRequest(RoutingContext ctx) {
    return ctx.data().containsKey(APP_AUTH_CONTEXT_KEY);
}
```

#### `ResourcePolicyAuthorizationHandler.java` — Dual path
```java
public void handle(RoutingContext context) {
    String resourceId = RoutingContextHelper.getId(context);

    if (RsRoutingContextHelper.isAppKeyRequest(context)) {
        // AppId path: authorization already verified by AppKeyAuthHandler via controlplane
        AppAuthContext appCtx = RsRoutingContextHelper.getAppAuthContext(context).get();
        if (!appCtx.covers(resourceId)) {
            context.fail(new DxForbiddenException("AppId does not have access to resource"));
            return;
        }
        context.next();
        return;
    }

    // JWT path: existing logic unchanged
    Optional<JwtData> jwtData = RsRoutingContextHelper.getJwtData(context);
    if (resourceId == null || jwtData.isEmpty()) {
        context.fail(new DxAuthException("Resource ID or token is missing"));
        return;
    }
    authorizationService.authorize(jwtData.get(), resourceId)
        .onSuccess(v -> context.next())
        .onFailure(context::fail);
}
```

#### `CheckItemAccessHandler.java` — Skip for AppId path
```java
public void handle(RoutingContext context) {
    if (RsRoutingContextHelper.isAppKeyRequest(context)) {
        // AppId path: access was fully verified at AppKeyAuthHandler via gRPC
        LOGGER.debug("AppId auth path — skipping CheckItemAccessHandler");
        context.next();
        return;
    }
    // JWT path: existing logic unchanged
    ...
}
```

#### `ItemAccessApplicableFilterHandlerNgsild.java` — Dual path for filters
```java
public void handle(RoutingContext context) {
    if (RsRoutingContextHelper.isAppKeyRequest(context)) {
        AppAuthContext appCtx = RsRoutingContextHelper.getAppAuthContext(context).get();
        String resourceId = RoutingContextHelper.getId(context);
        appCtx.getResource(resourceId).ifPresent(resource -> {
            RsRoutingContextHelper.setApplicableFilters(context, resource.getApplicableFilters());
            RsRoutingContextHelper.setAllowedAttributes(context, resource.getAllowedAttributes());
        });
        context.next();
        return;
    }
    // JWT path: existing logic unchanged
    ...
}
```

#### `AuditingHandler.java` — Tag authMethod
```java
String identity = RsRoutingContextHelper.isAppKeyRequest(ctx)
    ? "app:" + RsRoutingContextHelper.getAppAuthContext(ctx).get().getAppId().substring(0, 8) + "****"
    : "user:" + RsRoutingContextHelper.getJwtData(ctx).get().getSub();

auditEvent.put("identity", identity);
auditEvent.put("authMethod", RsRoutingContextHelper.isAppKeyRequest(ctx) ? "APP_KEY" : "JWT");
```

### 7.3 Updated Handler Chain

```
HTTP Request
    │
    ▼
[AppKeyAuthHandler]                        ← NEW
    │   X-App-Id present?
    │     → gRPC VerifyApp() or cache hit
    │     → set AppAuthContext → next
    │   X-App-Id absent?
    │     → next (fall through to JWT handler)
    ▼
[JwtAuthHandler]                           ← EXISTING (unchanged)
    │   Bearer JWT? → validate → set JwtData → next
    │   No JWT AND no AppAuthContext? → 401
    ▼
[ResourcePolicyAuthorizationHandler]       ← MODIFIED (dual path)
    │
    ▼
[CheckItemAccessHandler]                   ← MODIFIED (skip for AppId path)
    │
    ▼
[ItemAccessApplicableFilterHandlerNgsild]  ← MODIFIED (dual path for filters)
    │
    ▼
[ProviderValidationHandler]                ← unchanged
    │
    ▼
[Controller]                               ← unchanged
    │
    ▼
[AuditingHandler]                          ← updated (authMethod tag)
```

### 7.4 Wiring in ControllerFactory

```java
AppCredentialGrpcClient grpcClient = new AppCredentialGrpcClient(
    vertx,
    SocketAddress.inetSocketAddress(
        config.getInteger("controlPlaneGrpcPort"),
        config.getString("controlPlaneDomain")));

CachingAppCredentialClient cachingClient = new CachingAppCredentialClient(grpcClient);

// Start revocation stream at startup — runs for lifetime of dataplane instance
grpcClient.subscribeToRevocations(cachingClient);

AppKeyAuthHandler appKeyAuthHandler = new AppKeyAuthHandler(cachingClient);
// Pass as first handler in each controller's route chain
```

---

## 9. Controlplane Implementation

### 8.1 DB Schema

```sql
-- No changes to existing tables — just ensure these exist
app_credentials:
  app_id        UUID PRIMARY KEY          -- plain UUID, the credential itself
  user_id       UUID REFERENCES users(id)
  status        ENUM('ACTIVE','REVOKED','EXPIRED')
  created_at    TIMESTAMP
  expires_at    TIMESTAMP NULL

app_constraints:
  app_id        UUID REFERENCES app_credentials(app_id)
  resource_id   TEXT
  access_policy TEXT                      -- OPEN / SECURE / PII
  filters       JSONB
  attributes    JSONB[]
```

Note: The appId UUID is stored as plain text. **No hashing, no encryption.** The UUID's 122-bit randomness makes it unguessable. HTTPS protects it in transit.

### 8.2 New Classes

| Class | Responsibility |
|---|---|
| `AppVerifyGrpcService` | gRPC service impl — handles VerifyApp() and WatchRevocations() |
| `AppVerifyServiceImpl` | Business logic — queries app_credentials + app_constraints |
| `AppRevocationPublisher` | Called on admin revoke — pushes RevocationEvent to all active WatchRevocations streams |
| `AppVerifyControllerFactory` | Wires gRPC service into Vert.x gRPC server |

### 8.3 AppVerifyServiceImpl Logic

```java
public Future<AppVerifyResponse> verify(String appId) {
    return appCredentialRepository.findByAppId(appId)
        .compose(cred -> {
            if (cred == null) {
                return Future.failedFuture(new UnauthorizedException("AppId not found"));
            }
            if (cred.getStatus() != AppStatus.ACTIVE) {
                return Future.failedFuture(new UnauthorizedException("AppId is " + cred.getStatus()));
            }
            if (cred.getExpiresAt() != null && cred.getExpiresAt().isBefore(Instant.now())) {
                return Future.failedFuture(new UnauthorizedException("AppId has expired"));
            }

            return appConstraintRepository.findByAppId(appId)
                .map(constraints -> buildResponse(cred, constraints));
        });
}
```

### 8.4 AppRevocationPublisher — Pushing Revocations via gRPC Stream

```java
public class AppRevocationPublisher {
    // Holds all active WatchRevocations stream observers (one per connected dataplane instance)
    private final List<StreamObserver<RevocationEvent>> activeStreams = new CopyOnWriteArrayList<>();

    public void registerStream(StreamObserver<RevocationEvent> observer) {
        activeStreams.add(observer);
    }

    public void publish(String appId, String reason) {
        RevocationEvent event = RevocationEvent.newBuilder()
            .setAppId(appId)
            .setReason(reason)
            .setTimestamp(Instant.now().getEpochSecond())
            .build();

        // Push to every connected dataplane instance simultaneously
        activeStreams.forEach(observer -> {
            try {
                observer.onNext(event);
            } catch (Exception e) {
                LOGGER.warn("Failed to push revocation to a dataplane instance: {}", e.getMessage());
                activeStreams.remove(observer);
            }
        });
    }
}
```

### 8.5 Securing the gRPC Endpoint

The gRPC port is internal only — not exposed to the internet. Additionally:

```java
// Option 1: Network policy (Kubernetes NetworkPolicy restricts gRPC port to dataplane namespace)
// Option 2: mTLS on gRPC connection (upgrade path)

GrpcServerOptions options = new GrpcServerOptions()
    .setSsl(new SSLOptions()
        .setKeyCertOptions(new PemKeyCertOptions()
            .setCertPath("/certs/controlplane.crt")
            .setKeyPath("/certs/controlplane.key"))
        .setTrustOptions(new PemTrustOptions()
            .addCertPath("/certs/dataplane-ca.crt")));
```

### 8.6 Admin Revocation Flow

```
Admin clicks "Revoke" in DX portal
        │
        ▼
DELETE /iudx/auth/v2/app/{appId}  (admin API on controlplane)
        │
        ▼
AppCredentialRepository.revoke(appId)
  → UPDATE app_credentials SET status = REVOKED WHERE app_id = ?
        │
        ▼
AppRevocationPublisher.publish(appId, "ADMIN_REVOKED")
  → pushes RevocationEvent to ALL connected dataplane instances via gRPC stream
        │
        ▼
Each dataplane instance receives event
  → cachingClient.invalidate(appId)
        │
        ▼
Next request with this appId → cache miss → gRPC VerifyApp()
  → controlplane returns status=REVOKED → 401
  (end-to-end within seconds)
```

---

## 10. Security Analysis

### 9.1 Threat Model

| Threat | Impact | Mitigation |
|---|---|---|
| AppId intercepted in transit | High | HTTPS (TLS 1.2+) — appId encrypted in transit, never visible on wire |
| AppId stolen from user (e.g. leaked to GitHub) | High | Instant revocation via admin API + gRPC push; limit blast radius with per-resource constraints |
| gRPC port exposed publicly | High | Kubernetes NetworkPolicy — port only reachable from dataplane namespace |
| Cache staleness post-revocation | Low | gRPC stream delivers revocation within seconds; TTL (5 min) as fallback |
| Brute force appId | None | 122-bit UUID space + rate limiting (10 failed attempts = IP block) |
| Replay of valid request | Low | HTTPS prevents interception; revocation handles compromise |

### 9.2 Transport Requirements

- All client → dataplane calls carrying `X-App-Id` MUST use HTTPS (TLS 1.2+)
- All dataplane → controlplane gRPC calls MUST use a secured channel (network policy minimum; mTLS preferred)
- AppId MUST NOT appear in URL query parameters — header only
- AppId MUST NOT be logged in full — log first 8 chars + `****` suffix

---

## 11. API Contract

### 10.1 How Users Call the API

```http
# Using App Key
GET /iudx/v2/entities?id=urn:dx:rs:domain/rs/group/item-1
X-App-Id: 550e8400-e29b-41d4-a716-446655440000

# Using JWT (unchanged)
GET /iudx/v2/entities?id=urn:dx:rs:domain/rs/group/item-1
Authorization: Bearer eyJhbGciOiJSUzI1NiJ9...
```

Both return identical response shapes. The auth mechanism is transparent to the caller.

### 10.2 Error Responses

| Scenario | HTTP Status | Error Code |
|---|---|---|
| Missing both X-App-Id and Bearer token | 401 | `AUTH_MISSING` |
| Invalid / unknown appId | 401 | `INVALID_APP_ID` |
| Revoked appId | 401 | `APP_ID_REVOKED` |
| Expired appId | 401 | `APP_ID_EXPIRED` |
| AppId does not cover requested resource | 403 | `APP_RESOURCE_FORBIDDEN` |
| Both X-App-Id and Bearer provided | 400 | `AMBIGUOUS_AUTH` |

---

## 12. Configuration

### 11.1 Dataplane (`config.json`)

```json
{
  "appKeyAuth": {
    "enabled": true,
    "controlPlaneGrpcHost": "controlplane.dx.org",
    "controlPlaneGrpcPort": 9000,
    "cache": {
      "maxSize": 2000,
      "ttlMinutes": 5
    }
  }
}
```

### 11.2 Controlplane (`config.json`)

```json
{
  "grpcServer": {
    "enabled": true,
    "port": 9000,
    "allowedNamespace": "dx-dataplane"
  },
  "appVerify": {
    "enabled": true
  }
}
```

---

## 13. Open Questions

| # | Question | Options | Default Assumption |
|---|---|---|---|
| Q1 | Header name for appId? | `X-App-Id` vs `X-Api-Key` | `X-App-Id` |
| Q2 | Allow both JWT and AppId in same request? | Yes (JWT wins) / No (400) | No — return 400 |
| Q3 | Cache TTL? | 1 min / 5 min / 15 min | 5 min |
| Q4 | Which APIs support AppId? | All / Consumer-only / Configurable | Consumer-only (Latest + NGSILD Search) |
| Q5 | AppId for publish API? | Yes / No | No — provider must use JWT |
| Q6 | AppId expiry? | None / 90 days / 1 year / Configurable | Configurable per registration |
| Q7 | Who builds controlplane gRPC service? | Controlplane team / Shared ticket | Shared ticket |
| Q8 | mTLS on gRPC from day one? | Yes / No (network policy first) | Network policy Phase 1, mTLS Phase 2 |

---

## 14. Phased Rollout

### Phase 1 — Core AppId Auth (2–3 weeks)
- Controlplane: `AppVerifyGrpcService` + `AppVerifyServiceImpl` + admin revoke API
- Dataplane: `AppKeyAuthHandler` + `AppCredentialGrpcClient` + `CachingAppCredentialClient`
- Modify: `ResourcePolicyAuthorizationHandler`, `CheckItemAccessHandler`, `ItemAccessApplicableFilterHandlerNgsild`
- gRPC `VerifyApp()` RPC working end-to-end
- TTL cache (5 min) — revocation via gRPC stream
- AppId supported on Latest + NGSILD Search APIs only
- Network policy securing gRPC port

### Phase 2 — Hardening (1 week)
- mTLS between dataplane and controlplane on gRPC channel
- Rate limiting on failed VerifyApp() calls per appId
- AppId expiry support (configurable at registration)
- Audit log enhancements

### Phase 3 — Expansion (1–2 weeks)
- Extend AppId support to Download API
- DX portal UI for appId management (view, revoke, regenerate)
- Usage metrics per appId in audit dashboard

---

## 15. Summary Decision Table

| Decision | Choice | Rationale |
|---|---|---|
| Credential type | Plain UUID appId | 122-bit randomness — unguessable; same model as Stripe, GitHub, Twilio |
| Storage | Plain UUID in DB | No secret to hash — UUID IS the credential; HTTPS protects it in transit |
| Transport (dataplane → controlplane) | gRPC | Persistent connection; fast binary RPC; streaming revocation push |
| Revocation mechanism | gRPC WatchRevocations stream | Controlplane pushes to all dataplane instances within seconds; no polling |
| Caching | In-process Caffeine (5 min TTL) | No Redis dependency; per-instance; fast |
| gRPC security | Network policy → mTLS (Phase 2) | Simple start; strong upgrade path |
| Convergence point | After ItemAccessApplicableFilterHandler | All downstream handlers unchanged |
| Audit | authMethod + masked appId in audit event | Traceability without leaking full appId |
