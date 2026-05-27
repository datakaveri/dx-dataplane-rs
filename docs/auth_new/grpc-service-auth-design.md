# gRPC Service-to-Service Authentication — Design Document

**Branch:** `de/feat/grpc_service_auth`  
**Author:** Ankit Singh  
**Date:** 2026-05-26  
**Status:** Design — Implementation Pending

---

## 1. Problem Statement

dx-controlplane exposes a gRPC server on port `9090` with three endpoints:

| Endpoint | What it does |
|----------|-------------|
| `VerifyAppId` | Verifies AppId + AppSecret, returns DxUser |
| `CheckItemAccess` | Checks if a user can access a resource item |
| `ResolveDelegation` | Resolves delegation for a user pair |

**Current state:** These endpoints are completely unauthenticated. Any service (or attacker) on the same Docker network can call them freely. There is no way for dx-controlplane to know *which* service is calling or whether it is trusted.

**Goal:** dx-controlplane must be able to verify the identity of the calling service before processing any gRPC request.

---

## 2. Solution Overview — Service Identity Token

Each service that calls dx-controlplane gRPC must authenticate itself using a **service identity token** obtained from Keycloak.

- The token is obtained via OAuth2 **`client_credentials`** grant — no user involvement
- The token contains the service's identity in the **`azp`** (Authorized Party) claim — e.g., `azp = svc-dx-dataplane`
- The token is passed in the gRPC call metadata as `Authorization: Bearer <token>`
- dx-controlplane validates this token on every incoming gRPC call before processing it

This is **service-to-service authentication** — the service proves who it is, then dx-controlplane proceeds.

---

## 3. Full Flow — AppId Auth Example

Below is the complete request flow when a client authenticates with AppId + AppSecret:

```
Client (HTTP)                  dx-dataplane                    Keycloak                   dx-controlplane
     |                              |                               |                             |
     |  AppId + AppSecret           |                               |                             |
     |  (Basic Auth header)         |                               |                             |
     |----------------------------->|                               |                             |
     |                              |                               |                             |
     |                              |  POST /token                  |                             |
     |                              |  grant_type=client_credentials|                             |
     |                              |  client_id=svc-dx-dataplane   |                             |
     |                              |  client_secret=<secret>       |                             |
     |                              |------------------------------>|                             |
     |                              |                               |                             |
     |                              |  service_token                |                             |
     |                              |  (azp=svc-dx-dataplane,       |                             |
     |                              |   aud=dx-controlplane,        |                             |
     |                              |   scope=grpc:controlplane)    |                             |
     |                              |<------------------------------|                             |
     |                              |                               |                             |
     |                              |  gRPC VerifyAppId(appId, appSecret)                        |
     |                              |  metadata: Authorization: Bearer <service_token>            |
     |                              |----------------------------------------------------------->|
     |                              |                               |  [ServiceAuthInterceptor]   |
     |                              |                               |  1. validate token signature |
     |                              |                               |  2. check exp               |
     |                              |                               |  3. check aud=dx-controlplane|
     |                              |                               |  4. check scope=grpc:ctrl   |
     |                              |                               |  5. check azp in whitelist  |
     |                              |                               |  6. set callerService context|
     |                              |                               |                             |
     |                              |                               |  [VerifyAppId handler]      |
     |                              |                               |  look up appId in DB        |
     |                              |                               |  return DxUser              |
     |                              |<-----------------------------------------------------------|
     |                              |                               |                             |
     |                              |  gRPC CheckItemAccess(userId, entityId, did)               |
     |                              |  metadata: Authorization: Bearer <service_token>            |
     |                              |----------------------------------------------------------->|
     |                              |                               |  [ServiceAuthInterceptor]   |
     |                              |                               |  same validation again      |
     |                              |                               |                             |
     |                              |                               |  [CheckItemAccess handler]  |
     |                              |                               |  check policy in DB         |
     |                              |                               |  return result              |
     |                              |<-----------------------------------------------------------|
     |                              |                               |                             |
     |  200 OK + response           |                               |                             |
     |<-----------------------------|                               |                             |
```

**Key points:**
- dx-dataplane fetches the service token **once** and caches it (refreshes before expiry)
- The service token is passed on **every** gRPC call — both VerifyAppId and CheckItemAccess
- dx-controlplane validates the token **before** touching the database or business logic
- If the token is invalid or the `azp` is not in the whitelist → gRPC returns `UNAUTHENTICATED`, call is rejected

---

## 4. What the Service Identity Token Looks Like

When dx-dataplane calls Keycloak with `client_credentials`, the token it receives contains:

```json
{
  "iss": "https://v2.dev.keycloak.iudx.io/auth/realms/iudx-v2",
  "sub": "<UUID of svc-dx-dataplane service account>",
  "azp": "svc-dx-dataplane",
  "aud": ["dx-controlplane", "account"],
  "scope": "grpc:controlplane",
  "exp": 1748345678
}
```

| Claim | Value | Purpose |
|-------|-------|---------|
| `azp` | `svc-dx-dataplane` | Identifies **which service** fetched this token |
| `aud` | `dx-controlplane` | Restricts token to dx-controlplane only |
| `scope` | `grpc:controlplane` | Custom scope — proves intent to call gRPC |
| `exp` | timestamp | Token expires; dx-dataplane must refresh |

dx-controlplane checks all four. A token without `aud=dx-controlplane` or `scope=grpc:controlplane` is rejected even if signature is valid.

---

## 5. Components Needed

### 5.1 dx-dataplane side — "get and pass the service token"

| Component | Location | What it does |
|-----------|----------|-------------|
| `KeycloakTokenExchangeProvider` | `dx-common` | Fetches service token from Keycloak via `client_credentials`. Caches it in memory. Refreshes 60s before expiry. |
| `BearerTokenCallCredentials` | `dx-common` | gRPC `CallCredentials` — attaches `Authorization: Bearer <token>` to every gRPC call's metadata. |
| `AppIdVerificationClient` (updated) | `dx-common` | Existing gRPC client. Updated to accept a `serviceToken` parameter and attach it via `BearerTokenCallCredentials`. |
| Handler wiring | `dx-dataplane` verticles | `AppIdAuthHandler`, `AppIdItemAccessHandler`, `GrpcDelegationResolver`, `GrpcAppCredentialsResolver` — all updated to call `tokenProvider.getServiceToken()` before each gRPC call. |

**Config keys needed in dx-dataplane `commonConfig`:**
```json
"keycloakTokenUrl": "https://<keycloak-host>/auth/realms/<realm>/protocol/openid-connect/token",
"grpcClientId": "svc-dx-dataplane",
"grpcClientSecret": "<secret-from-keycloak>"
```

### 5.2 dx-controlplane side — "validate the service token"

| Component | Location | What it does |
|-----------|----------|-------------|
| `JwksCache` | `dx-controlplane` | Fetches Keycloak's public JWKS. Caches 600s. Force-refreshes on unknown `kid` (handles Keycloak key rotation). |
| `ServiceAuthInterceptor` | `dx-controlplane` | gRPC `ServerInterceptor`. Runs on every inbound call. Validates token signature, expiry, aud, scope, azp. Rejects with `UNAUTHENTICATED` if any check fails. |
| `GrpcServerVerticle` (updated) | `dx-controlplane` | Wires `ServiceAuthInterceptor` into `ServerBuilder` so every service is protected. |

**Config keys needed in dx-controlplane `GrpcServerVerticle` module:**
```json
"keycloakJwksUrl": "https://<keycloak-host>/auth/realms/<realm>/protocol/openid-connect/certs",
"grpcAllowedServiceClients": ["svc-dx-dataplane", "svc-ogc-resource-server", "svc-file-server", "svc-pdx"]
```

---

## 6. Token Lifecycle

```
dx-dataplane starts
      |
      |  first gRPC call arrives
      |
      v
KeycloakTokenExchangeProvider.getServiceToken()
      |
      |  no cached token?
      |  -----> POST Keycloak /token  (client_credentials)
      |         client_id=svc-dx-dataplane
      |         client_secret=<secret>
      |  <----- 200 OK  access_token + expires_in
      |
      |  cache token + record expiry time
      |
      v
BearerTokenCallCredentials(serviceToken)
      |
      v
gRPC call with Authorization: Bearer <serviceToken>

      ... (next call) ...

KeycloakTokenExchangeProvider.getServiceToken()
      |
      |  cached token still valid? (now < expiry - 60s)
      |  -----> return cached token immediately
      |
      v
gRPC call  (no Keycloak round-trip)

      ... (token near expiry) ...

KeycloakTokenExchangeProvider.getServiceToken()
      |
      |  cached token expires in < 60s?
      |  -----> POST Keycloak /token again  (refresh)
      |
      v
gRPC call with new token
```

---

## 7. Keycloak Setup Required

This feature requires changes in Keycloak **before** it will work. These are one-time setup steps.

### 7.1 Enable Token Exchange Feature (DevOps)

Add environment variable to Keycloak container and restart:
```
KC_FEATURES=token-exchange,admin-fine-grained-authz
```

> This requires a Keycloak container restart. Only DevOps can do this.

### 7.2 Keycloak Admin Console Steps (done by dev/admin)

Login to Keycloak Admin → realm `iudx-v2`:

**Step 1 — Create Client Scope `grpc:controlplane`**
- Left menu → **Client Scopes** → **Create client scope**
- Name: `grpc:controlplane`
- Type: `Optional`
- Protocol: `OpenID Connect`
- Save

**Step 2 — Create target client `dx-controlplane`**
- Left menu → **Clients** → **Create client**
- Client ID: `dx-controlplane`
- Client authentication: `OFF` (public)
- Uncheck all auth flows
- Save
- Go to **Permissions** tab → Enable Fine-Grained Permissions → toggle ON

**Step 3 — Create service client `svc-dx-dataplane`**
- Left menu → **Clients** → **Create client**
- Client ID: `svc-dx-dataplane`
- Client authentication: `ON` (confidential)
- Service accounts enabled: `ON`
- Uncheck all auth flows except service accounts
- Save
- **Client Scopes** tab → **Add client scope** → select `grpc:controlplane` → **Default**
  > ⚠️ Must be **Default**, not Optional. Optional scopes are only included if the client explicitly requests them via the `scope` parameter. Since `KeycloakServiceTokenProvider` does not send a `scope` parameter, an Optional scope will never appear in the token.
- **Client Scopes** tab → dedicated scopes → `grpc:controlplane` → **Mappers** → **Add mapper by configuration** → **Audience**
  - Name: `dx-controlplane-audience`
  - Included Client Audience: `dx-controlplane`
  - Add to access token: ON
  - Save
- **Credentials** tab → copy **Client Secret** (needed for config)

**Step 4 — Allow token exchange: `svc-dx-dataplane` → `dx-controlplane`**
- Go to client `dx-controlplane` → **Permissions** tab
- Click **token-exchange** permission link
- **Policies** → **Create policy** → **Client Policy**
  - Name: `allow-svc-dx-dataplane`
  - Clients: select `svc-dx-dataplane`
  - Decision: `Affirmative`
  - Save
- Back in token-exchange permission → add the policy → Decision Strategy: `Affirmative`

Repeat Step 3 and Step 4 for each additional service (`svc-ogc-resource-server`, `svc-file-server`, `svc-pdx`).

---

## 8. Verification

After Keycloak setup, verify before starting services:

### Test 1 — Get a service token
```bash
curl -s -X POST \
  "https://v2.dev.keycloak.iudx.io/auth/realms/iudx-v2/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=svc-dx-dataplane&client_secret=<your-secret>"
```
**Expected:** HTTP 200 with `access_token` in response.

### Test 2 — Decode and inspect the token
```bash
# Copy the access_token value, then:
echo "<access_token>" | cut -d. -f2 | base64 -d 2>/dev/null | python3 -m json.tool
```
**Expected claims:**
```json
{
  "azp": "svc-dx-dataplane",
  "aud": ["dx-controlplane", "account"],
  "scope": "grpc:controlplane ...",
  "exp": <future timestamp>
}
```

If `aud` does not contain `dx-controlplane` → audience mapper not added (Step 3).  
If `scope` does not contain `grpc:controlplane` → scope not assigned to client (Step 3).

---

## 9. Security Properties

| Property | How it is achieved |
|----------|-------------------|
| Only whitelisted services can call gRPC | `azp` check in `ServiceAuthInterceptor` against `grpcAllowedServiceClients` config |
| Token cannot be forged | RS256 signature validated against Keycloak public JWKS |
| Token cannot be reused across services | `aud=dx-controlplane` check — a token for a different audience is rejected |
| Token cannot be used for arbitrary Keycloak APIs | `scope=grpc:controlplane` — restricted, custom scope |
| Expired tokens are rejected | `exp > now` check |
| Adding a new service is safe | Add its client ID to `grpcAllowedServiceClients` config only after creating client in Keycloak |
| Key rotation is handled | `JwksCache.forceRefresh()` on unknown `kid` — no downtime during Keycloak key rotation |

---

## 10. What Does NOT Change

- The gRPC `.proto` file — no changes needed
- The gRPC service implementation (`AppIdVerificationGrpcService`) — no changes
- The HTTP auth flow (JWT tokens) — completely separate, unchanged
- How DxUser is returned — same structure
- The AppId/AppSecret themselves — still passed as gRPC request fields, not in metadata

---

## 11. Files to Create / Modify

### dx-common
| Action | File |
|--------|------|
| NEW | `auth/appid/client/BearerTokenCallCredentials.java` |
| NEW | `auth/appid/client/KeycloakTokenExchangeProvider.java` |
| MODIFY | `auth/appid/client/AppIdVerificationClient.java` — add overloads with `serviceToken` param |
| MODIFY | `auth/appid/handler/AppIdAuthHandler.java` — wire tokenProvider |
| MODIFY | `auth/authentication/resolver/GrpcDelegationResolver.java` — wire tokenProvider |
| MODIFY | `auth/authentication/resolver/GrpcAppCredentialsResolver.java` — wire tokenProvider |

### dx-controlplane
| Action | File |
|--------|------|
| NEW | `aaa/grpc/auth/JwksCache.java` |
| NEW | `aaa/grpc/auth/ServiceAuthInterceptor.java` |
| MODIFY | `aaa/grpc/GrpcServerVerticle.java` — wire interceptor |
| MODIFY | `dev/config-dev.json` — add keycloakJwksUrl + grpcAllowedServiceClients |

### dx-dataplane-rs
| Action | File |
|--------|------|
| MODIFY | `apiserver/ApiServerVerticle.java` — wire tokenProvider |
| MODIFY | `apiserver/ProxyApiServerVerticle.java` — wire tokenProvider |
| MODIFY | `apiserver/PublishedApiServerVerticle.java` — wire tokenProvider |
| MODIFY | `configs/config-dev.json` — add keycloakTokenUrl, grpcClientId, grpcClientSecret |

---

## 12. Open Questions Before Implementation

- [ ] What is the actual Keycloak host and realm for this environment?
- [ ] Will DevOps enable `KC_FEATURES` before we start? Or do we implement and test locally first?
- [ ] Should `grpcClientSecret` use Docker Secrets in the deployed config, or will DevOps manage that separately?
- [ ] Are there any other services besides the 4 listed that will call gRPC?
