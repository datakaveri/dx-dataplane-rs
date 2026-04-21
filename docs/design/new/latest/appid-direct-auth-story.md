# FEATURE STORY — AppId Direct Authentication at Dataplane

---

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | DX-XXXX |
| **Epic** | M2M Authentication Enhancements |
| **Type** | Feature |
| **Priority** | High |
| **Reporter** | Ankit Singh |
| **Assignee** | TBD |
| **Date Created** | 2026-04-09 |
| **Target Release** | TBD |
| **Story Points** | TBD |
| **Status** | Awaiting Approval |

---

## User Story

> **As a** machine-to-machine (M2M) client application developer,
> **I want to** authenticate directly at the dataplane by sending my AppId and AppSecret in request headers,
> **So that** my application can access data resources without having to manage JWT tokens, handle token expiry, or make a separate token-exchange call before every session.

---

## Business Value

M2M integrations are a growing use case in the DX platform. Currently, every M2M client must:

1. Call the controlplane token endpoint to exchange AppId + AppSecret for a signed JWT
2. Store and manage that JWT (expiry, refresh)
3. Then use the JWT on every dataplane request

This two-step process adds integration complexity for client developers and introduces an additional network call before any data can be fetched. For lightweight M2M clients (IoT devices, scripts, batch jobs), this overhead is significant.

**Approach B (this story)** removes that friction entirely. The client sends credentials directly on each request. The dataplane handles verification internally — the client has zero token management responsibility.

**Approach A (token exchange) remains fully functional and unchanged.** Clients that already use it are not affected.

---

## Background and Context

### What Already Exists

- **`app_credentials` table** (migration V38) — stores AppId, hashed AppSecret (SHA-512), expiry, and status (active / revoked / expired)
- **`app_constraints` table** (migration V45) — stores per-app access rules: which resources (entity IDs) the app can access and with what scope (data_access, asset_management, etc.)
- **Approach A (Token Exchange)** — fully built and working today via `AppTokenController` + `AppTokenServiceImpl` in the controlplane
- **No new database tables are needed** for this story

### Why gRPC for Verification

The dataplane needs to verify AppId + AppSecret against the controlplane's database on a cache miss. gRPC is chosen because:

- Strongly typed contract via protobuf — no ambiguous JSON parsing
- Async non-blocking stub — does not block the Vert.x event loop
- Efficient binary protocol — lower latency than HTTP for internal service calls
- Structured error codes in the response (INVALID_CREDENTIALS, REVOKED, EXPIRED)

---

## Detailed Solution Design

### Authentication Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        CLIENT REQUEST                           │
│              Headers: X-App-Id + X-App-Secret                   │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                   AppIdAuthHandler (Dataplane)                  │
│                                                                 │
│  1. Is ctx.user() already set? (JWT path ran first) → skip      │
│  2. Are X-App-Id and X-App-Secret headers present?              │
│     No  → 401 Unauthorized                                      │
│     Yes → validate AppId is a valid UUID format                 │
│                                                                 │
│  3. Check in-process cache (Guava, keyed by AppId)              │
│     HIT  → go to step 6                                         │
│     MISS → go to step 4                                         │
└────────────────────────────┬────────────────────────────────────┘
                             │ Cache MISS
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              gRPC Call → Controlplane                           │
│         AppIdVerificationGrpcService.VerifyAppId()              │
│                                                                 │
│  Validation (mirrors existing AppTokenServiceImpl logic):       │
│  a. Fetch app_credentials by AppId                              │
│  b. Check revokedAt != null → return REVOKED                    │
│  c. Check status != active → return EXPIRED                     │
│  d. Check expiry (Asia/Kolkata timezone) → return EXPIRED       │
│  e. SHA-512(inputSecret) == app_secret_hash → or INVALID        │
│                                                                 │
│  On success:                                                    │
│  f. Fetch all app_constraints for this AppId                    │
│  g. Resolve scopes → roles                                      │
│     data_access       → consumer                                │
│     asset_management  → provider                                │
│     user_management   → org_admin, consumer                     │
│     cos_admin_access  → all roles                               │
│  h. For each data_access entity UUID → fetch item metadata      │
│     (policies, iid, resourceServer, accessPolicy)               │
│  i. Return: map of { entityId → full metadata } for all         │
│     allowed entities + roles + scopes + expiry                  │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                AppIdAuthHandler (continued)                     │
│                                                                 │
│  4. Cache the result (TTL: 5 min, configurable)                 │
│  5. Populate ctx.user() with:                                   │
│     - sub = ownerId (user_id from app_credentials)              │
│     - iss = "appid"                                             │
│     - realm_access.roles = resolved roles                       │
│     - entity metadata map stored in context                     │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│               AppIdComparisonHandler (New)                      │
│   (follows existing GetIdFromBody / GetIdFromPath pattern)      │
│                                                                 │
│  1. Extract requested entity ID from request                    │
│     - Path param  (e.g. /entities/{entityId})                   │
│     - Query param (e.g. ?id=...)                                │
│     - Request body                                              │
│  2. Look up entity ID in the allowed entity map                 │
│     FOUND     → merge that entity's metadata (policies, iid,    │
│                 resourceServer, accessPolicy) into principal     │
│     NOT FOUND → 403 Forbidden                                   │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│        ItemAccessApplicableFilterHandlerNgsild                  │
│                   (UNCHANGED)                                   │
│                                                                 │
│  Sees 'policies' in principal → takes fast path                 │
│  No controlplane HTTP call needed                               │
│  No bearer token needed                                         │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
                      Data returned to client
```

---

## Scope

### In Scope

- HTTP dataplane routes: NGSILD entities, Latest
- Apps with access to one or multiple specific resource entity UUIDs
- OPEN and SECURE resource access (requires specific entity UUID, not wildcard)
- Cache invalidation via TTL (5 min default)

### Out of Scope

| Item | Reason |
|------|--------|
| gRPC external streaming endpoints | Separate story |
| Wildcard AppId (`entity_id = *`) with SECURE resources | No bearer token available for slow path; wildcard AppId restricted to OPEN resources only — SECURE must use Approach A |
| Production TLS for gRPC channel | Pending infra topology confirmation (see Open Questions) |
| Revocation push (real-time cache invalidation) | TTL-based invalidation sufficient for now; push mechanism is a future enhancement |

---

## Affected Services and Files

### dx-common

| File | Type | Description |
|------|------|-------------|
| `pom.xml` | Modified | Add Guava (not currently in dx-common), gRPC deps, protobuf-maven-plugin |
| `src/main/proto/appid_verification.proto` | New | Defines VerifyAppId RPC; response includes map of entityId → item metadata |
| `auth/appid/model/AppIdPrincipal.java` | New | Domain record: roles, scopes, entity metadata map, expiry |
| `auth/appid/cache/AppIdCacheService.java` | New | Guava in-process cache (TTL 5 min, configurable) keyed by AppId |
| `auth/appid/client/AppIdVerificationClient.java` | New | Async gRPC stub wrapper returning Vert.x Futures (non-blocking) |
| `auth/appid/handler/AppIdComparisonHandler.java` | New | Extracts entity ID from request; checks against allowed map; merges metadata into principal |
| `apiserver/AbstractApiServerVerticle.java` | Modified | Add `getAppIdAuthHandler()` hook + register `appIdAuth` OpenAPI security handler |

### dx-controlplane

| File | Type | Description |
|------|------|-------------|
| `aaa/appid/grpc/AppIdVerificationGrpcService.java` | New | Validates credentials; resolves scopes to roles; fetches item metadata for all allowed entity UUIDs; returns entity map |
| `aaa/appid/server/GrpcServerVerticle.java` | New | Vert.x verticle that hosts the gRPC server on port 8090 |
| `deploy/Deployer.java` | Modified | Register GrpcServerVerticle alongside existing verticles |
| `configs/config-dev.json` | Modified | Add `grpcPort: 8090` |

### dx-dataplane-rs

| File | Type | Description |
|------|------|-------------|
| `docs/openapi.yaml` | Modified | Add `appIdAuth` security scheme; update applicable routes to OR logic (JWT or AppId) |
| `auth/appid/handler/AppIdAuthHandler.java` | New | OpenAPI security handler; cache lookup; gRPC call on miss; populates ctx.user() |
| `apiserver/ApiServerVerticle.java` | Modified | Override `getAppIdAuthHandler()` to wire up cache + gRPC client + handler |
| `configs/config-dev.json` | Modified | Add `controlplaneHost`, `controlplaneGrpcPort`, `appIdCache` (maxSize, ttlMinutes) |

---

## Key Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Where to place gRPC client + cache | dx-common | Reusable by any service; follows existing dx-common patterns |
| Secret hashing algorithm | SHA-512 via DigestUtils.sha512Hex | Matches existing AppTokenServiceImpl exactly — no divergence |
| Multiple entity support | Entity map in gRPC response | All allowed entities returned at once; comparison handler picks the right one per request |
| Wildcard AppId + SECURE resources | B3 — OPEN only | Primary use case is always a specific entity UUID; avoids modifying slow path |
| Cache key | AppId only (not AppId + Secret) | Secret validated on first cache miss; subsequent hits within TTL window skip re-validation |
| Cache TTL | 5 minutes (configurable) | Bounds revocation propagation window to max 5 min |
| Downstream handlers | Unchanged | Principal shape matches JWT principal — fast path in ItemAccessApplicableFilterHandlerNgsild triggers naturally |

---

## Acceptance Criteria

### Functional

- [ ] M2M client sends `X-App-Id` + `X-App-Secret` headers → receives data response without any prior token exchange
- [ ] Same request with invalid AppId format (not a UUID) → 401 returned immediately
- [ ] Same request with wrong AppSecret → 401 returned (cache miss → gRPC → INVALID_CREDENTIALS)
- [ ] Same request with revoked AppId → 403 returned (REVOKED)
- [ ] Same request with expired AppId → 403 returned (EXPIRED)
- [ ] App with `data_access` to resource A and resource B → can access both using same AppId + AppSecret
- [ ] App with `data_access` to resource A → request for resource B → 403
- [ ] Wildcard AppId with OPEN resource → 200 returned
- [ ] Wildcard AppId with SECURE resource → 403 returned
- [ ] JWT auth on same routes → continues to work unchanged
- [ ] Both JWT and AppId headers on same request → JWT takes priority (AppIdAuthHandler skips if ctx.user() already set)

### Non-Functional

- [ ] First request (cache miss) completes within acceptable latency (gRPC + processing)
- [ ] Subsequent requests (cache hit) have negligible auth overhead
- [ ] Cache max size and TTL are configurable via `config-dev.json`
- [ ] AppSecret is never logged at any level in any service

### Operational

- [ ] `grpcPort` configurable in controlplane config
- [ ] `controlplaneHost` and `controlplaneGrpcPort` configurable in dataplane config
- [ ] gRPC server starts successfully when GrpcServerVerticle is deployed

---

## Test Scenarios

| Scenario | Expected Result |
|----------|----------------|
| Valid AppId + Secret, OPEN resource, entity in allowed list | 200 — data returned |
| Valid AppId + Secret, SECURE resource, entity in allowed list | 200 — data returned (fast path via embedded policies) |
| Valid AppId + Secret, entity NOT in allowed list | 403 |
| Wrong AppSecret | 401 |
| Revoked AppId | 403 |
| Expired AppId | 403 |
| AppId not a valid UUID | 401 |
| Missing X-App-Id header | 401 |
| Missing X-App-Secret header | 401 |
| Valid JWT + no AppId headers | 200 — JWT path, unchanged |
| Both JWT and AppId headers present | 200 — JWT takes priority |
| AppId with access to multiple resources | Each resource accessible individually |
| Wildcard AppId + OPEN resource | 200 |
| Wildcard AppId + SECURE resource | 403 |
| Cache hit (second request, same AppId) | 200 — no gRPC call made |
| gRPC server unavailable on cache miss | 503 — authentication service unavailable |

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| gRPC channel plaintext in production | High — AppSecret exposed on wire | OQ3 to be resolved before prod deploy; RMQ alternative documented as fallback |
| Cache hit skips secret re-validation | Medium — wrong secret passes within TTL window if AppId known | AppId is a UUID — hard to guess; TTL bounds the window; acceptable trade-off |
| Revocation not propagated immediately | Medium — revoked AppId still works for up to 5 min | TTL is configurable; real-time push can be added in a future story |
| gRPC server unavailable | High — all AppId cache-miss requests fail | Fail fast with 503; JWT auth unaffected |

---

## Open Questions

| # | Question | Owner | Blocking Prod? |
|---|----------|-------|----------------|
| OQ3 | gRPC channel TLS for production — same Kubernetes cluster (TLS at ingress, no code change) or cross-cluster (TLS needed in ManagedChannelBuilder — one-way or mTLS)? | Ankit / Infra team | Yes — must resolve before prod deploy. Dev can proceed with plaintext. |

---

## Definition of Done

- [ ] All code reviewed and merged in dx-common, dx-controlplane, dx-dataplane-rs
- [ ] Unit tests written: AppIdAuthHandler, AppIdVerificationGrpcService, AppIdComparisonHandler, AppIdCacheService
- [ ] Integration test: end-to-end AppId → dataplane → gRPC → controlplane → data returned
- [ ] All test scenarios in the table above passed
- [ ] AppSecret confirmed not present in any logs
- [ ] Config keys documented in config-dev.json for all 3 services
- [ ] Design doc updated with final implementation notes
- [ ] OQ3 resolved before production deployment

---

## References

- Full implementation design: `docs/design/new/latest/appid-auth-via-dx-common.md`
- RMQ alternative (not chosen, documented for reference): `docs/design/new/latest/appid-rmq-alternative.md`
