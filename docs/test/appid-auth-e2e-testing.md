# AppId Auth — End-to-End Testing Guide

This guide walks through testing the AppId Direct Auth (Approach B) implementation end-to-end.
It covers: verifying test data in the DB, starting both services, testing the gRPC layer in
isolation, and finally testing the full HTTP flow through the dataplane.

---

## Architecture Recap

```
curl (X-App-Id / X-App-Secret)
    │
    ▼
dx-dataplane-rs  :8443 / :8444
    │  AppIdAuthHandler
    │  ├─ cache hit → use cached principal
    │  └─ cache miss → gRPC call ──────────────────────────────────────┐
    │                                                                   │
    │  AppIdEntityComparisonHandler                     dx-controlplane :9090 (gRPC)
    │  └─ merge entity metadata into ctx.user()         AppIdVerificationGrpcService
    │                                                       ├─ validates credentials (sha512)
    ▼                                                       ├─ checks status / expiry / revoked
Elasticsearch (data)                                        └─ fetches item metadata from ES
```

**What the gRPC response must contain for the HTTP flow to succeed:**

- `success = true`
- `principal.roles` contains `"consumer"` (so `AuthorizationHandler.forRoles()` passes)
- `principal.entity_metadata_map` has an entry for the entity UUID being queried (so
  `AppIdEntityComparisonHandler` can merge `policies`, `accessPolicy`, `resourceServer`, `iid`
  into `ctx.user().principal()`)

---

## Prerequisites

Before starting, make sure the following services are reachable:

| Service | Default address | Used by |
|---|---|---|
| PostgreSQL | `localhost:5433` | controlplane (app_credentials, app_constraints) |
| Elasticsearch | `localhost:24034` (or as configured) | controlplane (item metadata), dataplane (data queries) |
| Zookeeper | `localhost:2181` | both (Hazelcast event-bus clustering) |

---

## Step 1 — Verify / Insert Test Data in PostgreSQL

### 1a. Check if an AppId already exists

Connect to the DB and run:

```sql
\c iudx_db
SET search_path TO aaa, public;

-- List all app credentials
SELECT app_id, user_id, status, role, expiry_at, revoked_at
FROM app_credentials
ORDER BY created_at DESC
LIMIT 10;

-- If rows exist, check their constraints
SELECT ac.app_id, ac2.scope, ac2.entity_type, ac2.entity_id
FROM app_credentials ac
JOIN app_constraints ac2 ON ac.app_id = ac2.app_id
WHERE ac.status = 'active'
  AND ac.expiry_at > now()
  AND ac2.scope = 'data_access'
LIMIT 10;
```

If you find a row with `status='active'`, a future `expiry_at`, and a `data_access` constraint
with a valid entity UUID — you can use that `app_id` directly. You will need the **plain-text
secret** that was used when the app was created (the DB only stores the sha512 hash).

### 1b. Insert fresh test data (if none exists or you don't have the plain-text secret)

Pick a plain-text secret you will use in all curl commands, e.g. `TestSecret@123`.

**Compute the sha512 hash** (no trailing newline — use `-n`):

```bash
echo -n "TestSecret@123" | sha512sum
# e.g. 3c9909afec25354d551...
```

**Find a valid `user_id`** (must be a real UUID in your users table):

```sql
-- In dx-controlplane DB, find a consumer user
SELECT id FROM users WHERE role = 'consumer' LIMIT 5;
-- OR any existing user UUID is fine for testing
```

**Find a valid entity UUID** (must exist in Elasticsearch — your test resource item):

```sql
-- You can also just pick one you know exists in ES from past queries
-- e.g. a resource you already use for JWT-auth testing
```

**Insert:**

```sql
SET search_path TO aaa, public;

-- Step 1: insert the credential
INSERT INTO app_credentials (app_id, user_id, app_secret_hash, expiry_at, status, role)
VALUES (
  gen_random_uuid(),
  '<user-uuid>',
  '<sha512-hash-of-TestSecret@123>',
  '2027-12-31 23:59:59',
  'active',
  'consumer'
)
RETURNING app_id;
-- NOTE DOWN the returned app_id UUID — you will use it as X-App-Id header

-- Step 2: insert the data_access constraint
-- Use the app_id returned above and an entity UUID that exists in Elasticsearch
INSERT INTO app_constraints (app_id, scope, entity_type, entity_id)
VALUES (
  '<app_id from above>',
  'data_access',
  'RESOURCE',
  '<elasticsearch-entity-uuid>'
);

-- Verify
SELECT ac.app_id, ac.status, ac.expiry_at, c.scope, c.entity_id
FROM app_credentials ac
JOIN app_constraints c ON ac.app_id = c.app_id
WHERE ac.app_id = '<app_id>';
```

At the end of this step you should have:

| Variable | Value |
|---|---|
| `APP_ID` | UUID from `RETURNING app_id` |
| `APP_SECRET` | `TestSecret@123` (plain text) |
| `ENTITY_ID` | UUID inserted into `app_constraints.entity_id` |

---

## Step 2 — Build and Start dx-controlplane

```bash
cd /home/ankit/Documents/3-Nov-2022/dx-controlplane

# Confirm correct branch
git branch --show-current
# Expected: de/feat/gRPC_stubs

# Build (skip tests for speed)
mvn clean package -DskipTests -q

# Start
java -jar target/dx-controlplane-dev.jar -conf dev/config-dev.json
```

**What to look for in the startup logs:**

```
AppId gRPC server started on port 9090
```

If you see this, the gRPC server is up. If it fails, check:
- Port 9090 is not already in use: `ss -tlnp | grep 9090`
- PostgreSQL is reachable on the configured host/port
- Elasticsearch is reachable (needed by `ItemService`)

---

## Step 3 — Test gRPC in Isolation (before touching dataplane)

This is the most important debugging step. If gRPC does not return the right response here,
the dataplane will fail for unrelated reasons.

### Install grpcurl

```bash
# Download
curl -L https://github.com/fullstorydev/grpcurl/releases/download/v1.9.1/grpcurl_1.9.1_linux_x86_64.tar.gz \
  | tar xz
sudo mv grpcurl /usr/local/bin/
grpcurl --version
```

### 3a. Check service is up

```bash
grpcurl -plaintext localhost:9090 list
# Expected output:
# org.cdpg.dx.auth.appid.v1.AppIdVerificationService
```

### 3b. Happy path — valid credentials

```bash
grpcurl -plaintext \
  -d '{
    "app_id": "<APP_ID>",
    "app_secret": "<APP_SECRET>"
  }' \
  localhost:9090 \
  org.cdpg.dx.auth.appid.v1.AppIdVerificationService/VerifyAppId
```

**Expected response:**

```json
{
  "success": true,
  "principal": {
    "appId": "<APP_ID>",
    "ownerId": "<user-uuid>",
    "roles": ["consumer"],
    "scopes": ["data_access"],
    "entityMetadataMap": {
      "<ENTITY_ID>": "{\"iid\":\"<ENTITY_ID>\",\"accessPolicy\":\"OPEN\",\"resourceServer\":\"...\",\"policies\":[...]}"
    }
  }
}
```

**Critical checks on this response:**

1. `success` must be `true`
2. `roles` must contain `"consumer"` — without this, `AuthorizationHandler` will reject the request
3. `entityMetadataMap` must be **non-empty** and contain your `ENTITY_ID` as a key — without this,
   `AppIdEntityComparisonHandler` will return 403
4. The value for `ENTITY_ID` must be valid JSON with `policies` key — without `policies`,
   `ItemAccessApplicableFilterHandlerNgsild` won't take the fast path

### 3c. Wrong secret

```bash
grpcurl -plaintext \
  -d '{"app_id": "<APP_ID>", "app_secret": "wrongpassword"}' \
  localhost:9090 \
  org.cdpg.dx.auth.appid.v1.AppIdVerificationService/VerifyAppId
# Expected: { "success": false, "errorCode": "INVALID_CREDENTIALS" }
```

### 3d. Invalid UUID format

```bash
grpcurl -plaintext \
  -d '{"app_id": "not-a-uuid", "app_secret": "anything"}' \
  localhost:9090 \
  org.cdpg.dx.auth.appid.v1.AppIdVerificationService/VerifyAppId
# Expected: { "success": false, "errorCode": "INVALID_CREDENTIALS" }
```

**Do not proceed to Step 4 until 3b returns `success=true` with a non-empty `entityMetadataMap`.**

---

## Step 4 — Build and Start dx-dataplane-rs

```bash
cd /home/ankit/Documents/3-Nov-2022/dx-dataplane-rs

# Confirm correct branch
git branch --show-current
# Expected: fe/feat/code-modulartity/grpc_impl

# Build
mvn clean package -DskipTests -q

# Start
java -jar target/dx-dataplane-rs-dev.jar -conf configs/config-dev.json
```

**What to look for in startup logs:**

```
AppId gRPC client configured: localhost:9090
```

If you don't see this line, `getAppIdAuthHandler()` was not called — check that
`ApiServerVerticle` and `ProxyApiServerVerticle` are in the modules list in `config-dev.json`.

---

## Step 5 — curl Tests Against the Dataplane

Set your variables first:

```bash
APP_ID="<uuid from Step 1>"
APP_SECRET="TestSecret@123"
ENTITY_ID="<entity-uuid from Step 1>"
```

### 5a. Happy path — NGSILD search with AppId

```bash
curl -k -v -X GET \
  "https://localhost:8443/ngsi-ld/v2/entities?id=${ENTITY_ID}&attrs=speed&timerel=during&time=2023-01-01T00:00:00Z&endtime=2023-12-31T00:00:00Z" \
  -H "X-App-Id: ${APP_ID}" \
  -H "X-App-Secret: ${APP_SECRET}"
```

Expected: `200 OK` with data (or `204` if no data exists for that time range — both are correct,
it means auth passed).

### 5b. Latest data endpoint

```bash
curl -k -v -X GET \
  "https://localhost:8443/ngsi-ld/v2/entities/${ENTITY_ID}/attrs" \
  -H "X-App-Id: ${APP_ID}" \
  -H "X-App-Secret: ${APP_SECRET}"
```

### 5c. Missing `X-App-Secret` → 401

```bash
curl -k -v -X GET \
  "https://localhost:8443/ngsi-ld/v2/entities?id=${ENTITY_ID}&attrs=speed" \
  -H "X-App-Id: ${APP_ID}"
# Expected: 401 Unauthorized
```

### 5d. Missing `X-App-Id` → 401

```bash
curl -k -v -X GET \
  "https://localhost:8443/ngsi-ld/v2/entities?id=${ENTITY_ID}&attrs=speed" \
  -H "X-App-Secret: ${APP_SECRET}"
# Expected: 401 Unauthorized
```

### 5e. Wrong entity ID (not in app's constraints) → 403

```bash
curl -k -v -X GET \
  "https://localhost:8443/ngsi-ld/v2/entities?id=00000000-0000-0000-0000-000000000000&attrs=speed" \
  -H "X-App-Id: ${APP_ID}" \
  -H "X-App-Secret: ${APP_SECRET}"
# Expected: 403 Forbidden
```

### 5f. Wrong credentials → 401

```bash
curl -k -v -X GET \
  "https://localhost:8443/ngsi-ld/v2/entities?id=${ENTITY_ID}&attrs=speed" \
  -H "X-App-Id: ${APP_ID}" \
  -H "X-App-Secret: wrongpassword"
# Expected: 401 Unauthorized
```

### 5g. JWT flow must be unaffected (regression check)

```bash
curl -k -v -X GET \
  "https://localhost:8443/ngsi-ld/v2/entities?id=${ENTITY_ID}&attrs=speed" \
  -H "Authorization: Bearer <valid-jwt>"
# Expected: same 200 as before this feature was added
```

### 5h. Cache test — confirm second request uses cache

Send the happy-path request twice:

```bash
curl -k -X GET \
  "https://localhost:8443/ngsi-ld/v2/entities?id=${ENTITY_ID}&attrs=speed" \
  -H "X-App-Id: ${APP_ID}" \
  -H "X-App-Secret: ${APP_SECRET}"

# Immediately again
curl -k -X GET \
  "https://localhost:8443/ngsi-ld/v2/entities?id=${ENTITY_ID}&attrs=speed" \
  -H "X-App-Id: ${APP_ID}" \
  -H "X-App-Secret: ${APP_SECRET}"
```

In the dataplane logs, the second request should show:
```
AppId cache hit for appId=<APP_ID>
```
and **no** gRPC call log for the second request.

### 5i. Proxy/Gateway verticle (port 8444)

The same AppId should work on the gateway routes:

```bash
curl -k -v -X GET \
  "https://localhost:8444/rsp/ngsi-ld/v2/entities?id=${ENTITY_ID}&attrs=speed" \
  -H "X-App-Id: ${APP_ID}" \
  -H "X-App-Secret: ${APP_SECRET}"
```

---

## Dataplane Log Messages Reference

| Log message | File | Means |
|---|---|---|
| `AppId gRPC client configured: localhost:9090` | `ApiServerVerticle` | Handler wired correctly on startup |
| `AppId cache hit for appId=...` | `AppIdAuthHandler` | Guava cache returned a hit; no gRPC call made |
| `AppId verification failed for appId=...` | `AppIdAuthHandler` | gRPC returned `success=false` |
| `gRPC verification error for appId=...` | `AppIdAuthHandler` | gRPC call itself threw an exception (controlplane down?) |
| `AppId entity metadata merged for entityId=...` | `AppIdEntityComparisonHandler` | Metadata successfully merged into user principal |
| `AppId auth: principal has no metadata for entityId=...` | `AppIdEntityComparisonHandler` | Entity ID not in the app's constraints; will return 403 |
| `AppId auth: entity ID not found in routing context` | `AppIdEntityComparisonHandler` | ID extraction handler didn't run before this handler |

---

## Troubleshooting

### `entityMetadataMap` is empty in gRPC response

The `AppIdVerificationGrpcService` fetches item metadata via `itemService.getItemWithAccessChecks()`.
This calls Elasticsearch. Check:

1. Is Elasticsearch up and reachable from the controlplane?
2. Does the `entity_id` in `app_constraints` exactly match the `id` field of the document in
   Elasticsearch? (It must be the full UUID, e.g. `aeaa98f3-1234-...` not a prefixed IRI.)
3. Look at controlplane logs for: `Could not fetch item metadata for entityId ...`

### 401 even though credentials look correct

The `app_secret_hash` stored in the DB must equal `sha512(plain-text-secret)`.
Verify the hash directly:

```bash
# Recompute (make sure there's no newline — use -n)
EXPECTED=$(echo -n "TestSecret@123" | sha512sum | awk '{print $1}')
echo $EXPECTED

# Compare to what's in DB
psql -U postgres -d iudx_db -c "SELECT app_secret_hash FROM aaa.app_credentials WHERE app_id = '<APP_ID>';"
```

### 403 from dataplane even though gRPC returns success

The `entity_id` in `entityMetadataMap` must exactly match what `RoutingContextHelper.getId(ctx)`
returns in the dataplane. Common mismatch: entity ID in DB is a plain UUID but the API request
`id` parameter carries a full IRI like `urn:dx:...`. Check what format your existing JWT-auth
tests use for entity IDs in query params.

### `AppId gRPC client configured` never appears in dataplane logs

The `getAppIdAuthHandler()` override in `ApiServerVerticle` was not called.
Check that `AbstractApiServerVerticle.start()` calls `getAppIdAuthHandler()` and registers the
returned handler as the `appIdAuth` security handler. Trace the call in
`AbstractApiServerVerticle.java`.

### Port 9090 already in use

```bash
ss -tlnp | grep 9090
# Kill the process or change grpcPort in dev/config-dev.json + controlplaneGrpcPort in
# dx-dataplane-rs/configs/config-dev.json (both must match)
```

---

## Quick Reference: Key Config Values

**dx-controlplane** `dev/config-dev.json`:
```json
{
  "id": "org.cdpg.dx.aaa.grpc.GrpcServerVerticle",
  "grpcPort": 9090
}
```

**dx-dataplane-rs** `configs/config-dev.json` (inside `commonConfig`):
```json
"controlplaneHost": "localhost",
"controlplaneGrpcPort": 9090,
"appIdCacheMaxSize": 1000,
"appIdCacheTtlMinutes": 5
```

Both values must agree.
