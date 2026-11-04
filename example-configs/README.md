# `config.json` Field Reference — dx-dataplane-rs

Complete reference for every field in [`configs/config-dev.json`](./configs/config-dev.json): what
it configures, what breaks if it is wrong, and where to obtain its value. Written for whoever
deploys and operates the dataplane resource server.

**Where to start.** §1 explains how config blocks reach each verticle — the most common source of
"the key is set, but the code reads `null`". §2 documents every field individually. §3 groups
fields by category (credentials, URLs, tuning knobs, feature flags) so a whole category can be
checked at once. §4 lists dead fields and known inconsistencies between the config and the code.

This example file mirrors the working `configs/config-dev.json` (the dev deployment's actual
config, which is the reference for this file's shape) — same keys, same module order, with all
credentials and infrastructure hosts blanked. It differs deliberately in two ways: modules the
working file keeps commented out are **omitted** here rather than carried as dead text (see
*No Postgres* in §4), and the file is strict, comment-free JSON. When the working config changes
shape, re-sync this file and this document.

Every field was traced to the code that reads it. Where a field is consumed by the `dx-common`
dependency rather than this repo, the class is named and marked *(dx-common)*.

Code references name the **consuming class only, never a line number** — line numbers go stale on
the first unrelated edit, and this document is meant to outlive that. For a field marked
*(dx-common)* there will be no grep hit in this repo — that is expected, and is the reason the
marker exists. Those live in the `org.cdpg.dx:dx-common` dependency.

## 0. Document header

| |                                                                                 |
|---|---------------------------------------------------------------------------------|
| **Service** | dataplane resource server (dx-dataplane-rs)                                     |
| **Code repo / branch** | `datakaveri/dx-dataplane-rs` — `dev` (source of truth), `stable/v2.3` (release) |
| **Config schema version** | `1.0` (top-level `version`)                                                     |
| **Maintainer / point of contact** | *Ankit Singh*                                                                   |
| **Last updated** | 2026-07-28                                                                      |

Dependency note: `org.cdpg.dx:dx-common:1.0.0-SNAPSHOT` supplies `BaseDeployer`, `ConfigHelper`,
`ElasticsearchVerticle`, `BaseDataBrokerVerticle`, `AbstractApiServerVerticle`, `JwksResolver`,
`AppIdVerificationClient`, `KeycloakServiceTokenProvider` and `AppIdRevocationConsumer`. Several
config keys are consumed only there — the classes do not exist in this repo, which is expected,
not a packaging error.

**Files in this directory:**

| File | Purpose |
|---|---|
| [`configs/config-dev.json`](./configs/config-dev.json) | Example config — the subject of this document. Synced from the working `configs/config-dev.json` with credentials blanked |
| [`configs/config-test.json`](./configs/config-test.json) | **Legacy** — pre-`dx-common` module layout (`DatabaseVerticle`, `AuthenticationVerticle`, …). Does not match the current codebase; see §4 |
| [`configs/keystore.jks`](./configs/keystore.jks), `configs/keystore.p12` | Example TLS keystores referenced by the `keystore` module keys (password `password`) |
| [`configs/example-env`](./configs/example-env) | Env vars for docker-compose (`RS_URL`, `LOG_LEVEL`, `RS_JAVA_OPTS`, AWS keys) |

## 1. Top-level structure

| Key | Configures | Consumed by |
|---|---|---|
| `version` | Config schema marker | Nothing — see §4 |
| `zookeepers` | Zookeeper hosts for clustered Vert.x | `BaseDeployer` *(dx-common)* |
| `clusterId` | Hazelcast discovery group for the cluster | `BaseDeployer` *(dx-common)* |
| `host` | *(nothing — dead key, see §4; the public host comes from the `--host` CLI flag)* | — |
| `commonConfig` | Cross-cutting URLs, controlplane gRPC endpoint, AppId cache/revocation, accepted JWT issuers | The three API-server verticles (via `required`) |
| `modules` | Verticle deployment list; each entry's `required` array selects which top-level blocks are merged into that verticle's `config()` | `BaseDeployer` + `ConfigHelper` *(dx-common)* |

**The `required` array is the key mechanism.** `ConfigHelper.mergeRequiredConfigs` *(dx-common)*
merges each block named in a module's `required` array **flat** into that module's `config()`. A
verticle can only read a top-level block listed in its own `required`. Two consequences:

1. Adding a key to `commonConfig` without `"required": ["commonConfig"]` on the consuming module
   yields a silent `null` (or the code default).
2. The merge **overwrites** — a key that exists both in the module entry and in a `required`
   block takes the *block's* value, not the module's. So a `chunkMaxItems` in `commonConfig`
   silently beats the `chunkMaxItems` written directly on `PublishedApiServerVerticle`.

| Module | `required` blocks |
|---|---|
| `S3Verticle` | *(none — settings inlined)* |
| `DataBrokerVerticle` | *(none)* — but see §4: its AppId cache keys live in `commonConfig` and never reach it |
| `ElasticsearchVerticle` | *(none — settings inlined)* |
| `ApiServerVerticle` | `commonConfig` |
| `ProxyApiServerVerticle` | `commonConfig` |
| `PublishedApiServerVerticle` | `commonConfig` |

Deployment mode: `BaseDeployer` *(dx-common)* parses `--config`, `--isClustered`, `--host` and
`--modules` from the command line. `zookeepers`/`clusterId` are only read when
`--isClustered true`; in standalone mode they are ignored entirely.

---

## 2. Field blocks

### `version`

- **Type / format:** string
- **Required:** no
- **Purpose:** Schema marker only. No code reads it.
- **Example value:** `"1.0"`
- **Failure mode:** none.
- **Notes / gotchas:** Documentation-only. Keep it accurate for humans, not for the app.

### `zookeepers`

- **Type / format:** array of strings; hostname (optionally `host:port`), no scheme
- **Required:** conditional — only with `--isClustered true`
- **Purpose:** Zookeeper ensemble used by `BaseDeployer` *(dx-common)* for Hazelcast discovery
  when building the clustered Vert.x instance. Ignored in standalone mode.
- **Example value:** `["zookeeper"]`
- **Default if omitted:** none — clustered startup NPEs reading the array; standalone unaffected.
- **Failure mode:** `Could not join cluster` logged fatal; no verticle deploys.

### `clusterId`

- **Type / format:** string
- **Required:** conditional — clustered mode only
- **Purpose:** Hazelcast Zookeeper discovery **group** name (`ZookeeperDiscoveryProperties.GROUP`)
  in `BaseDeployer` *(dx-common)*. All members that should form one cluster must share it.
- **Example value:** `"iudx-rs-cluster"`
- **Failure mode:** members with different values silently form separate clusters — event-bus
  proxies (`ElasticsearchService`, `DataBrokerService`, …) find no handler at request time.
- **Notes / gotchas:** Unlike the controlplane (where `clusterId` is dead), this repo's deploy
  path **does** consume it.

### `host` *(top-level)*

- **Type / format:** string
- **Required:** no — **dead key**
- **Purpose:** none. The cluster public host is taken from the **`--host` CLI option** (default
  `localhost`), not from config. See §4.
- **Failure mode:** none — but editing it and expecting the advertised host to change is a trap.
  Change the `--host` argument in the launch command / Dockerfile instead.

---

### `commonConfig` — cross-cutting block

Delivered (flat-merged) to the three API-server verticles via `required`. Keys below are read
from the merged per-verticle `config()`.

### `commonConfig.dxApiBasePath`

- **Required:** yes — See §4.
- **Example value:** `"/ngsi-ld/v2"`
- **Failure mode:** none observed — the API base paths come from the OpenAPI specs in `docs/`.

### `commonConfig.timeLimit`

- **Type / format:** string; **three comma-separated parts, no spaces:**
  `<mode>,<anchor-date>,<days>`
- **Required:** yes
- **Purpose:** Controls the default temporal window applied to searches that arrive without an
  explicit time range. Read in `ControllerFactory` and handed to `SearchServiceImpl`; parsed in
  `QueryDecoder` / `QueryDecoderNew` (part 3) and `TemporalQueryFiltersDecorator` (parts 1–2):
  - `mode` — `production` or `test` (constants `PROD_INSTANCE`/`TEST_INSTANCE` *(dx-common)*).
    `production` anchors the default window at *now*; `test` anchors it at `anchor-date` so a
    static test dataset stays queryable.
  - `anchor-date` — ISO-8601 datetime; the pretend "now" used in `test` mode.
  - `days` — integer; the width of the default window in days, and the cap validated against.
- **Example value:** `"test,2020-10-22T00:00:00Z,25000"`
- **Default if omitted:** none — `null` reaches the query decoders and temporal queries fail.
- **Failure mode:** a mode other than `production`/`test` throws
  `DxBadRequestException: invalid timeLimit config passed` **per request**, not at startup — the
  server boots healthy and every temporal search 400s.
- **Change impact:** production deployments must switch the mode to `production`; leaving `test`
  pins search results to the anchor date.
- **Notes / gotchas:** The shipped `days` value of `25000` (~68 years) effectively disables the
  default-window cap — a dev convenience; size it deliberately for production.

### `commonConfig.tenantPrefix`

- **Type / format:** string; Elasticsearch index-name prefix
- **Required:** yes
- **Purpose:** Every per-asset index is named `<tenantPrefix>__<assetId>`
  (`IndexNameCreation`); also passed to `ElasticOnboardingController`, which creates indices
  under that name on onboarding.
- **Example value:** `"iudx-v2"`
- **Default if omitted:** `null` → indices named `null__<assetId>`; searches miss all data.
- **Change impact:** **data migration.** Changing it orphans every existing index — reindex or
  alias before switching. Must stay consistent with whatever provisioned existing indices.


### `commonConfig.supportEmail`

- **Type / format:** string; email address
- **Required:** no — `AbstractApiServerVerticle` *(dx-common)* defaults to
  `support@cdpg.org.in`
- **Purpose:** Substituted for the `${SUPPORT_EMAIL}` token in each served OpenAPI spec (the
  docs' contact block).
- **Failure mode:** documentation only — wrong support address in the published docs.

### `commonConfig.gatewayurl` / `ngsildurl` / `publishedurl`

- **Type / format:** string; **host + path, no scheme** — the spec supplies `https://`
- **Required:** yes in practice (default is the literal `example.com`)
- **Purpose:** Each API-server verticle substitutes *its own* key for the `${HOSTNAME}` token in
  *its own* OpenAPI spec (`AbstractApiServerVerticle` *(dx-common)*,
  `getBaseUrlConfigKey()` override per subclass):

  | Key | Verticle | Spec file |
  |---|---|---|
  | `ngsildurl` | `ApiServerVerticle` | `docs/openapi.yaml` |
  | `gatewayurl` | `ProxyApiServerVerticle` | `docs/openapiForGateway.yaml` |
  | `publishedurl` | `PublishedApiServerVerticle` | `docs/openapiforpublish.yaml` |

- **Example value:** `"rs.example.org"`, `"rs.example.org/rsp"`, `"rs.example.org/published"`
- **Default if omitted:** `"example.com"` — docs advertise the wrong host; the API itself is
  unaffected.
- **Failure mode:** confined to docs — "Try it out" and the copy-paste `curl` examples hit the
  wrong host. Including a scheme yields `https://https://…` in every example.
- **Change impact:** documentation only; must match each server's public ingress route.

### `commonConfig.corsAllowedOrigin`

- **Type / format:** array of strings; origins or `*`
- **Required:** **yes — validated at startup.** `AbstractApiServerVerticle` *(dx-common)* throws
  `IllegalArgumentException: Missing required configuration: 'corsAllowedOrigin' key must be
  defined in config` during verticle start.
- **Purpose:** CORS allow-list. If the list contains `*`, credentials are disabled and all
  origins allowed; otherwise each origin is registered and credentials allowed.
- **Example value:** `["https://dashboard.example.org"]`
- **Failure mode:** missing → the API-server verticle fails to deploy (this is one of the few
  fail-fast fields in the file). Wrong origins → browser CORS errors while `curl` works — a
  frequent false "the API is down" report.
- **Notes / gotchas:** The shipped example is `["*", "http://localhost:4001"]` — the `*` makes
  the second entry redundant, and **must be tightened before production.**

### `commonConfig.controlPlaneDomain`

- **Type / format:** string; **full URL with scheme**, no trailing slash
- **Required:** yes
- **Purpose:** Base URL for HTTP calls to the controlplane. `CheckItemAccessHandler` appends
  `/iudx/acl/apd/v2/access_request/has_access` (access-request check) and `/iudx/v2/cat/item`
  (item metadata fetch); read in `ControllerFactory`, `PublishedControllerFactory`,
  `EntityControllerfactory` and `GatewayControllerFactory` and threaded into the latest,
  download, NGSI-LD and publish paths.
- **Example value:** `"https://cdpg.org.in/controlplane"`
- **Default if omitted:** `null` → malformed URLs; item-access checks fail and restricted-data
  requests are denied or 500.
- **Change impact:** cross-service — must match the controlplane's public ingress. Scheme
  **required** here (unlike the three `*url` fields above — easy to swap by mistake).

### `commonConfig.controlplaneHost` + `commonConfig.controlplaneGrpcPort`

**Documented as a pair — the controlplane gRPC endpoint.**

- **Type / format:** string (hostname, no scheme) / int
- **Required:** yes — `controlplaneGrpcPort` is read with `config.getInteger(...)` and unboxed,
  so a missing key **NPEs during verticle start** in all three API-server verticles.
- **Purpose:** Endpoint of the controlplane's `GrpcServerVerticle`, dialed by
  `AppIdVerificationClient` *(dx-common)* for AppId/AppSecret verification, item-access checks
  and delegation lookups. The connection is **plaintext gRPC** — both services are expected to
  share a cluster network; do not point this across the public internet.
- **Example value:** `"controlplane.svc.local"` / `9090`
- **Failure mode:** wrong host/port → AppId (Basic auth) logins and item-access checks fail with
  gRPC `UNAVAILABLE` while plain JWT traffic keeps working — a confusing partial outage.
- **Change impact:** cross-service — must match the controlplane's `GrpcServerVerticle.grpcPort`
  (its default is `9090`).
- **Notes / gotchas:** The client resolves the hostname via `InetAddress.getByName` before
  dialing, so Docker Swarm service names containing underscores work despite gRPC's authority
  validation.

### `commonConfig.keycloakTokenUrl` + `grpcClientId` + `grpcClientSecret`

**Documented as a credential set — service-to-service auth for the gRPC calls above.**

- **Type / format:** string (full URL) / string / string (secret)
- **Required:** yes — consumed by `KeycloakServiceTokenProvider` *(dx-common)*, constructed in
  all three API-server verticles.
- **Purpose:** Client-credentials grant against Keycloak; the resulting service token is
  attached to every gRPC call so the controlplane can authenticate the caller.
- **Expected value:** `keycloakTokenUrl` is the realm's
  `protocol/openid-connect/token` endpoint; `grpcClientId` is a **confidential Keycloak client
  with service accounts enabled** (conventionally `svc-dx-dataplane`).
- **Example value:** `"https://cdpg.org.in/auth/realms/<realm>/protocol/openid-connect/token"` /
  `"svc-dx-dataplane"` / *(secret)*
- **How to obtain:** DevOps creates the client in the tenant realm; the secret is on the
  client's Credentials tab.
- **Failure mode:** missing/wrong → token fetch fails and every gRPC-backed path (AppId auth,
  item access, delegation) is rejected; JWT-only traffic still works.
- **Change impact:** **cross-service.** The client ID must also appear in the controlplane's
  `GrpcServerVerticle.grpcAllowedServiceClients` allow-list — being a valid Keycloak client is
  not enough. Rotate secret in Keycloak and config together.

### `commonConfig.appIdCacheMaxSize` / `appIdCacheTtlMinutes`

- **Type / format:** int / long — **tuning knobs**
- **Required:** no — defaulted
- **Purpose:** Size and TTL of the in-process AppId verification and item-access caches
  (`AppIdCacheService`, `AppIdItemAccessCacheService` *(dx-common)*), created in this repo's
  `DataBrokerVerticle`.
- **Example value:** `1000` / `5`
- **Default if omitted:** `1000` / `5`.
- **Failure mode:** TTL too high widens the window in which a revoked AppId still authenticates
  (bounded by the RMQ revocation consumer below); too low hammers the controlplane gRPC API.
- **Notes / gotchas:** **These keys are currently inert where they sit.** They live in
  `commonConfig`, but the consuming `DataBrokerVerticle` does not list
  `required: ["commonConfig"]` — it reads its own module config and falls back to the defaults,
  which happen to equal the example values. See §4 before tuning.

### `commonConfig.appIdRevokeExchange` / `appIdRevokeQueue`

- **Type / format:** string / string; RabbitMQ names
- **Required:** no — both default to `"revoked-appid"`
- **Purpose:** `AppIdRevocationConsumer` *(dx-common)*, started by this repo's
  `DataBrokerVerticle` on the **internal vhost**, declares the queue (durable), binds it to the
  exchange with routing key `##`, and invalidates the AppId caches on each message.
- **Failure mode:** exchange absent on the broker → the bind fails and revocations never reach
  this node; revoked AppIds keep working until cache TTL expiry — a security-relevant silent
  failure. The queue is auto-declared, but the **exchange must already exist**.
- **Change impact:** cross-service — must match the exchange the controlplane publishes
  revocations to (its `commonOptions.appIdRevokeExchange`, same `"revoked-appid"` default).
- **Notes / gotchas:** Same `required` caveat as the cache keys above — see §4.

### `commonConfig.issuers`

- **Type / format:** object keyed by issuer string; each issuer value has `type`, `jwksUrl`,
  `audience`; three scalar settings sit alongside at the same level
- **Required:** yes
- **Purpose:** The set of JWT issuers this service accepts. Consumed by `JwksResolver`
  *(dx-common)*, wired by `AbstractApiServerVerticle` into `MultiIssuerJwtAuthHandler` and the
  auth-v2 resolvers. A token whose `iss` has no entry here is rejected 401 regardless of
  validity.
- **Expected value:** two entries —
  - the controlplane issuer (e.g. `<domain>/controlplane`) with `"type": "remote"` and its
    `/iudx/v2/auth/jwks` endpoint as `jwksUrl`;
  - the Keycloak realm (`https://<domain>/auth/realms/<realm>`) with `"type": "remote"` and the
    realm's `protocol/openid-connect/certs` endpoint.
- **Per-issuer keys:** `type` — `remote` (fetch JWKS from `jwksUrl`) or `internal`; this service
  issues no tokens, so both entries are `remote`. `jwksUrl` — required for `remote`.
  `audience` — **no consumer found**; empty arrays shipped, and the value is ignored. See §4.
- **Scalar settings inside the map:**
  - `jwtIgnoreExpiry` *(bool — feature flag)* — disables expiry checking. Default `false`.
    **The shipped example sets `true` — never deploy that to production**; expired tokens would
    be accepted indefinitely, silently.
  - `jwksRefreshIntervalMs` *(int, ms — tuning knob)* — JWKS cache refresh. Default `600000`
    (10 min). Example ships `21600000` (6 h): after a Keycloak key rotation, valid tokens are
    rejected until refresh.
  - `leeway` — **wrong key name; dead.** The code reads `jwtLeeway`
    (`AuthConstants.JWT_LEEWAY` *(dx-common)*), defaulting to 60 s. The shipped `"leeway": 60`
    is ignored — harmless only because it equals the default. See §4.
- **Failure mode:** issuer missing → every request bearing that issuer's token 401s.
  `jwksUrl` wrong → signature validation fails for that issuer only.
- **Change impact:** cross-service — the controlplane entry must track the controlplane's
  `cosDomain`/JWKS route; the Keycloak entry must track the realm.

---

## `modules[]` — deployment list

Consumed by `BaseDeployer` *(dx-common)*. Scaffolding keys first, then per-module keys.

#### `modules[].id`

- **Type / format:** string; fully-qualified Java class name
- **Required:** yes
- **Failure mode:** `ClassNotFoundException` at startup; deployment aborts. **`ElasticsearchVerticle`
  resolves from `dx-common`, not this repo** — correct, not a missing class.
- **Notes / gotchas:** Deployment order matters here: `DataBrokerVerticle` must precede the
  API-server verticles, because it registers the AppId caches in `AppIdCacheHolder` that the
  auth handlers grab at wiring time. `BaseDeployer` deploys strictly in array order — keep the
  API servers last.

#### `modules[].verticleInstances`

- **Type / format:** int — **tuning knob**
- **Required:** yes — read unboxed; a missing value NPEs the deploy loop.
- **Expected value:** 1 for infrastructure verticles; the example runs `ElasticsearchVerticle`
  at 2. Count instances when sizing anything pooled downstream (Elasticsearch connections, the
  broker's `requestedChannelMax`).

#### `modules[].isWorkerVerticle`

- **Type / format:** bool
- **Required:** yes (all `false` in this config)
- **Purpose:** Runs the verticle on the worker pool; if `true`, `threadPoolName` and
  `threadPoolSize` on the same entry configure the pool (`BaseDeployer` *(dx-common)*).

#### `modules[].required`

- **Type / format:** array of top-level block names
- **Purpose / failure mode:** see §1 — **the highest-value mechanism in this document.** A key
  can be present and correct in `commonConfig` and still read back as `null` because the
  consuming module never listed the block. And a same-named key in the block **overwrites** the
  module-level value.

---

### `DataBrokerVerticle` — RabbitMQ block

Connection fields are consumed by `BaseDataBrokerVerticle` *(dx-common)*; the AppId wiring and
`adapterQueryPublishExchange` by this repo's `DataBrokerVerticle` subclass.

#### `dataBrokerIP` / `dataBrokerPort`

- **Type / format:** string (hostname, no scheme) / int (AMQP port)
- **Required:** yes (`dataBrokerPort` unboxed — NPE if missing)
- **Failure mode:** connection retries forever; publish/consume paths degrade.
- **Notes / gotchas:** The shipped `24568` is the dev broker's non-standard AMQP port; a plain
  in-cluster RabbitMQ uses `5672` (or `5671` for TLS).

#### `dataBrokerManagementPort` + `portSsl`

- **Type / format:** int / bool
- **Required:** management port yes; `portSsl` defaults `false`
- **Purpose:** The RabbitMQ **management HTTP API** endpoint used by `RabbitWebClient`
  *(dx-common)* for queue/exchange/user administration. `portSsl` selects HTTPS **for this
  management client only** — it does not affect the AMQP connection.
- **Failure mode:** admin calls (subscription/queue management) fail with connection refused
  while AMQP publish/consume still works — a confusing partial outage.
- **Notes / gotchas:** The shipped `443` + `portSsl: true` reflects the dev setup, where the
  management API sits behind an HTTPS ingress; against a plain in-cluster broker use `15672` +
  `false`. Keep the pair consistent — `443` with `portSsl: false` (or vice versa) fails the TLS
  handshake in a way that looks like a network problem.

#### `dataBrokerUserName` + `dataBrokerPassword`

**Documented as a pair.**

- **Type / format:** string / string (secret)
- **Required:** yes
- **How to obtain:** RabbitMQ admin credentials from the databroker provisioning step.
- **Privileges required:** configure/write/read on all three vhosts below. The runtime creates
  per-user queues/exchanges and RabbitMQ **users** via the management API
  (`RabbitClient.createUserIfNotExist` *(dx-common)*), so the account needs the
  **`administrator` tag**, not just `management`.
- **Failure mode:** `ACCESS_REFUSED` on connect; or — with a merely `management`-tagged user —
  startup and consumption succeed and only subscription/user creation fails with a 403 buried
  in the logs.

#### `prodVhost` / `internalVhost` / `externalVhost`

- **Type / format:** string; vhost names (uppercase by convention)
- **Required:** yes — all three clients are built at startup, so all three vhosts must exist
  and be connectable even where unused.
- **Expected value:** `<TENANTPREFIX>`, `<TENANTPREFIX>-INTERNAL`, `<TENANTPREFIX>-EXTERNAL`
- **Purpose, per vhost:**
  - **`prodVhost`** — the data vhost: per-asset exchanges (checked via
    `NGSILDDataPublishServiceImpl.listExchange`) and the queues/exchanges managed for
    subscribers through `DataBrokerServiceImpl` *(dx-common)*.
  - **`internalVhost`** — this service's own messaging: audit-log publishing
    (`AuditingHandler` → `publishMessageInternal`), adapter-query publishing
    (`RabbitClient.publishEx`), and the AppId revocation consumer.
  - **`externalVhost`** — connect-only; passed into `DataBrokerServiceImpl` for
    consumer-facing operations.
- **Failure mode:** `NOT_ALLOWED - vhost ... not found`; the affected client fails to start.
- **Notes / gotchas:** Case-sensitive. The uppercase convention differs from the lowercase
  `tenantPrefix` used for Elasticsearch — a frequent copy-paste error.

##### RabbitMQ topology to provision

Must exist **before** startup (the service declares only the revocation queue itself):

| Object | Vhost | Notes |
|---|---|---|
| `auditing` exchange (direct, durable) | internal | Audit events published with routing key `##` (a **literal**, not a wildcard — the queue binding must match exactly). Overridable via optional `auditingExchange`/`auditingRoutingKey` keys, read with defaults in `ControllerFactory`/`PublishedControllerFactory` |
| `revoked-appid` exchange (direct, durable) | internal | AppId revocations from the controlplane. Queue + `##` binding auto-declared by `AppIdRevocationConsumer` |
| `rpc-adapter-requests` exchange | internal | Target of `adapterQueryPublishExchange` below |
| per-asset exchanges / subscriber queues | prod | Created at runtime through the management API |

#### `connectionTimeout` / `requestedHeartbeat` / `handshakeTimeout` / `requestedChannelMax` / `networkRecoveryInterval`

- **Type / format:** int (ms; heartbeat in seconds; channel max a count) — **tuning knobs**
- **Required:** yes — read unboxed in `BaseDataBrokerVerticle` *(dx-common)*; omitting any NPEs
  at deploy. Ship the example values (`6000`, `60`, `6000`, `5`, `500`) unless proven wrong.
- **Failure mode:** timeouts too low → reconnect storms under load; `requestedChannelMax` too
  low → channel exhaustion with many consumers; heartbeat too high → dead connections linger.

#### `automaticRecoveryEnabled`

- **Required:** no — **dead key.** `BaseDataBrokerVerticle` *(dx-common)* hardcodes
  `setAutomaticRecoveryEnabled(true)` and never reads this. See §4.

#### `brokerAmqpIp` / `brokerAmqpPort`

- **Type / format:** string (host) / int
- **Required:** yes (unboxed int)
- **Purpose:** The externally reachable AMQP(S) endpoint **advertised to subscribers** in
  `registerQueue`/`registerExchange` responses (`DataBrokerServiceImpl` *(dx-common)*) — as
  distinct from the in-cluster `dataBrokerIP` this service dials itself.
- **Failure mode:** subscribers receive connection details that do not resolve outside the
  cluster. Do not collapse the two pairs into one.

#### `adapterQueryPublishExchange`

- **Type / format:** string; exchange name
- **Required:** yes in practice
- **Purpose:** Exchange that adapter/RPC queries are published to, on the **internal vhost**
  (`RabbitClient.publishEx` *(dx-common)*, set by this repo's `DataBrokerVerticle`).
- **Example value:** `"rpc-adapter-requests"`
- **Default if omitted:** `null` → publish fails at request time.
- **Change impact:** cross-service — the adapter side must consume from the same exchange.

---

### `ElasticsearchVerticle` — connection block *(dx-common)*

`databaseIP`, `databasePort`, `databaseUser`, `databasePassword` — consumed by
`ElasticsearchVerticle` *(dx-common)*, which registers `ElasticsearchService` on the event bus.

- **Required:** yes (`databasePort` unboxed)
- **Example value:** ES host, `9200`, `rs-user`, *(secret)* — host and credentials are blanked
  in this example.
- **Privileges required:** read/write/`view_index_metadata` (plus create-index for onboarding)
  on `<tenantPrefix>__*`. Read-only is not enough — onboarding and publish write.
- **Failure mode:** wrong credentials → `security_exception` on every search; missing index →
  `index_not_found_exception` per asset.
- **Notes / gotchas:** The username key is `databaseUser` here, but other DX services' database
  blocks spell it `databaseUserName` — **not interchangeable**, and the classic silent-null trap
  when copy-pasting a block between services. `verticleInstances: 2` in the example is
  deliberate (search parallelism); an optional `serviceAddress` key exists for deploying a
  second instance, unused here.

---

### `S3Verticle` — object storage block

`s3AccessKey`, `s3SecretKey`, `s3Bucket`, `s3Region` — consumed by `S3Verticle` (this repo),
which builds `S3FileOpsHelper` and registers `S3FileService`. Used by the publish path for file
uploads/downloads.

- **Required:** yes for the publish/file paths
- **How to obtain:** AWS IAM (or S3-compatible store) credentials with get/put/delete on the
  bucket.
- **Failure mode:** wrong credentials → S3 operations fail at request time
  (`403 AccessDenied`); startup is unaffected.
- **Notes / gotchas:** An optional **`s3Endpoint`** key (not in the example) points at
  S3-compatible storage (MinIO etc.). **`s3ProxyTimeoutMs` does not belong in this block** —
  it is read from the *PublishedApiServerVerticle's* config (`PublishedControllerFactory`,
  default `240000`), so the copy here is dead. See §4. A parallel `MinioVerticle`
  (`org.cdpg.dx.cloudstorage.minio.verticle.MinioVerticle`) exists with `minio*` keys and an
  optional `minioProxyTimeoutMs`; it is not deployed in this config.

---

### API-server verticles — shared keys

`ApiServerVerticle` (port 8443, NGSI-LD search/latest/download APIs), `ProxyApiServerVerticle`
(8444, gateway/RSP APIs), `PublishedApiServerVerticle` (8445, data-publish APIs). All extend
`AbstractApiServerVerticle` *(dx-common)*; keys below are read there unless noted.

**All three need `"required": ["commonConfig"]`** — they read `controlplaneHost`,
`controlplaneGrpcPort`, `keycloakTokenUrl`, `corsAllowedOrigin`, `issuers`, `timeLimit`, … from
the merged block. Without it the verticle **fails to deploy** (`corsAllowedOrigin` check, or NPE
unboxing `controlplaneGrpcPort`).

#### `ssl` + `keystore` + `keystorePassword`

- **Type / format:** bool / path / string (secret)
- **Required:** `ssl` defaults `false` (plain HTTP); the other two are required when `true`
- **Purpose:** TLS for the HTTP listener; JKS keystore, path relative to the working directory.
- **Example value:** `true` / `"configs/keystore.jks"` / `"password"`
- **Failure mode:** missing/incorrect keystore or password → HTTP server fails to start; the
  error is logged and **the process keeps running without that listener** — probe the port,
  not just the process.
- **Notes / gotchas:** In Kubernetes, TLS usually terminates at the ingress — `ssl: false`
  there is normal. The shipped keystore/password are examples; never production values.

#### `httpPort`

- **Type / format:** int
- **Required:** no — defaults per verticle: `8443` / `8444` / `8445`
- **Failure mode:** collision → `BindException: Address already in use`; must match the chart's
  service/probe ports.

#### `urnPrefix`

- **Type / format:** string
- **Required:** no — defaults `urn:dx:dataplane:` (API + proxy), `urn:dx:dataplanePublish:`
- **Purpose:** Prefix for the `type` URN in error responses (`URNGenerator` *(dx-common)*).
- **Notes / gotchas:** The shipped values (`dx:dataplane:` etc.) drop the `urn:` prefix the
  code defaults carry — cosmetic, but clients that pattern-match error URNs will notice. Pick
  one convention.

#### `timeout`

- **Type / format:** long; ms — **tuning knob**
- **Required:** no — defaults: 600 000 (API), 1 000 000 (proxy), 600 000 (published)
- **Purpose:** Router-level request timeout (`TimeoutHandler`, responds 408).
- **Failure mode:** too low → long downloads/publishes 408 mid-transfer; too high → slow
  clients pin server resources.

#### `maxDaysSync` / `maxDaysAsync`

- **Type / format:** int; days — **tuning knobs**
- **Required:** no — defaults vary by read site: `ControllerFactory` 20/365 (API server),
  `EntityControllerfactory` 10/365 and `GatewayControllerFactory` 365/365 (proxy).
- **Purpose:** Maximum queryable time-window for synchronous vs async search requests.
- **Failure mode:** requests spanning more days are rejected as bad requests; too high lets a
  single sync query scan years of data and time out.
- **Notes / gotchas:** The shipped `ApiServerVerticle` value is `25000` (~68 years) — like the
  `timeLimit` days part, it effectively disables the sync cap for dev. Size it for production.

#### `ProxyApiServerVerticle.isEmailRequiredInRequest`

- **Type / format:** bool — **feature flag**
- **Required:** no — defaults `false` (read in `ProxyApiServerVerticle`, threaded through
  `ControllerFactoryProxy` into `EntitiesController` and `GatewayController`)
- **Purpose:** When `true`, the authenticated user's `email` claim is copied out of the decoded
  JWT and added as an `email` key on the query JSON published to the adapter over RMQ, next to
  `instanceId` / `publicKey` / `api` / `applicableFilters`. **This is the email needed for
  SATA** — SATA identifies the requesting user by email, so the flag must be `true` on
  SATA-serving deployments and can stay `false` everywhere else.
- **Example value:** `true`
- **Failure mode:** none at startup — a missing key is simply `false`. With the flag off, SATA
  receives queries with no `email` and cannot attribute the request. With it on, requests
  authenticated by **appId/secret rather than a JWT carry no `email` claim**, so the key is
  omitted for those (a debug line is logged); only Bearer-token requests are enriched.
- **Change impact:** proxy server only (8444) — the API and published servers ignore it.
- **Notes / gotchas:** The value comes straight from the token's `email` claim, so the Keycloak
  client must request the `email` scope; a token issued without it enriches nothing. Enabling
  the flag puts a personal identifier on the RMQ payload — keep it off unless the downstream
  consumer actually needs it.

#### `isAdexInstance` *(on the API-server module entries)*

- **Required:** no — **no consumer found.** See §4. (A `production` boolean that used to sit
  alongside it is already gone from the working config; `production` matters only as the *mode
  word inside* `commonConfig.timeLimit`.)

#### `PublishedApiServerVerticle.chunkMaxItems` / `chunkMaxBytes`

- **Type / format:** int / int (**megabytes**, converted ×1024×1024 in
  `NGSILDDataPublishController`) — **tuning knobs**
- **Required:** no — defaults `2000` items / `5` MB (`PublishedControllerFactory`)
- **Purpose:** Flush thresholds for the streaming bulk-publish path — a batch is written when
  either limit is hit.
- **Failure mode:** too large → memory pressure and giant ES bulk requests; too small → bulk
  overhead dominates.
- **Notes / gotchas:** Two traps, and **both are live in this config.** (1) The unit is **MB**,
  not bytes — the key name lies. (2) Because `required` blocks overwrite module keys (§1), the
  `commonConfig` copies (`25000` / `8000`) silently replace the values written on this module
  (`2000` / `30`) — the effective limits are **25 000 items / 8 000 MB per chunk**, and editing
  the module entry changes nothing. Keep these keys in **one** place; see §4.

#### `PublishedApiServerVerticle.s3ProxyTimeoutMs` / `minioProxyTimeoutMs`

- **Type / format:** long; ms — **tuning knobs**
- **Required:** no — defaults `240000` / `180000` (`PublishedControllerFactory`)
- **Purpose:** Event-bus proxy timeouts for S3/MinIO operations — the ceiling on a single file
  transfer through the publish path.
- **Notes / gotchas:** These belong **on this module** (or in `commonConfig`), not on the
  `S3Verticle` entry where the example currently parks `s3ProxyTimeoutMs`. See §4.

#### `auditingExchange` / `auditingRoutingKey` *(optional, any API-server module)*

- **Required:** no — default `"auditing"` / `"##"` (`ControllerFactory`,
  `PublishedControllerFactory`)
- **Purpose:** Where `AuditingHandler` *(dx-common)* publishes audit logs (internal vhost).
- **Failure mode:** mismatch with the broker binding → audits published but never routed —
  **silent**; neither side logs an error.

---

## 3. Extra requirements by field category

### Credentials

Four credential sets, each in a different system. Never reuse one across systems.

| Credential | System | Detail |
|---|---|---|
| `dataBrokerUserName` + `dataBrokerPassword` | RabbitMQ | needs the **`administrator` tag** — the service creates users/queues via the management API |
| `databaseUser` + `databasePassword` (Elastic module) | Elasticsearch | read/write/create on `<tenantPrefix>__*` |
| `s3AccessKey` + `s3SecretKey` | S3 / compatible | get/put/delete on `s3Bucket` |
| `grpcClientId` + `grpcClientSecret` | Keycloak | confidential client, service accounts enabled; must also be allow-listed in the controlplane's `grpcAllowedServiceClients` |

`keystore` + `keystorePassword` is a fifth secret but file-based — a JKS mounted next to the
config.

### Domains / URLs

Scheme and slash rules are enforced inconsistently — follow this table literally.

| Field | Scheme? | Must match |
|---|---|---|
| `ngsildurl` / `gatewayurl` / `publishedurl` | **no** — spec adds `https://` | each server's public ingress |
| `controlPlaneDomain` | **yes**, no trailing slash | controlplane ingress |
| `keycloakTokenUrl` | yes — full token endpoint | Keycloak realm |
| `issuers` keys + `jwksUrl` | as issued in tokens / full URL | controlplane `cosDomain`; Keycloak realm |
| `controlplaneHost` | no — bare hostname (gRPC) | controlplane gRPC service |
| `dataBrokerIP` / `brokerAmqpIp` | no | in-cluster broker / public AMQPS endpoint |

**Cross-service values** — change in lockstep with the other service or break:
`controlplaneHost`+`controlplaneGrpcPort`, `grpcClientId` (controlplane allow-list),
`appIdRevokeExchange` (controlplane publisher), `controlPlaneDomain`, the `issuers` entries,
`adapterQueryPublishExchange` (adapter consumers), `tenantPrefix` (existing ES indices),
`brokerAmqpIp`/`brokerAmqpPort` (what subscribers are told to dial).

### Tuning knobs

| Field | Default | Symptom if wrong |
|---|---|---|
| `verticleInstances` | — | multiplies downstream connections; >1 on singleton verticles duplicates work |
| `appIdCacheMaxSize` / `appIdCacheTtlMinutes` | 1000 / 5 | high TTL → revoked AppIds linger (bounded by RMQ revocation); low → gRPC hammering |
| `timeout` (per API server) | 600k / 1M / 600k ms | 408s mid-download vs pinned resources |
| `maxDaysSync` / `maxDaysAsync` | site-dependent (see §2) | rejected queries vs runaway scans |
| `chunkMaxItems` / `chunkMaxBytes` (MB!) | 2000 / 5 | memory pressure vs bulk overhead |
| `s3ProxyTimeoutMs` / `minioProxyTimeoutMs` | 240k / 180k | large file transfers abort early |
| `jwksRefreshIntervalMs` | 600 000 | after key rotation, valid tokens rejected until refresh |
| RMQ timeouts / channel max / heartbeat | as shipped | reconnect storms, channel exhaustion, stalled messages |

### Feature flags

| Flag | Default | Note |
|---|---|---|
| `ssl` (per API server) | `false` | requires `keystore` + `keystorePassword` when `true` |
| `portSsl` (databroker) | `false` | HTTPS for the **management API only**, not AMQP |
| `issuers.jwtIgnoreExpiry` | `false` | **never `true` in production** — shipped example has `true` |
| `timeLimit` mode word | — | `test` pins search "now" to the anchor date; must be `production` in prod |
| `isEmailRequiredInRequest` (proxy) | `false` | adds the token's `email` to the RMQ query — **the email SATA needs**; JWT requests only |

## 4. Findings — fields to resolve

These issues live in the **working config's shape** (and therefore in this synced example);
fixing them means changing `configs/config-dev.json` on `dev` first, then re-syncing:


| Field | Notes |
|---|---|
| `host` (top-level) | The public host is the `--host` CLI option; this key is ignored |
| `commonConfig.dxApiBasePath` | Base paths come from the OpenAPI specs |
| `commonConfig.timeLimitForAsync` | The async window is `maxDaysAsync` |
| `commonConfig.redisKeyPrefix` | Read site commented out with the disabled `RedisVerticle` |
| `modules[].isAdexInstance` | No read site under any name |
| `automaticRecoveryEnabled` | Hardcoded `true` in `BaseDataBrokerVerticle` *(dx-common)* |
| `issuers.<issuer>.audience` | Parsed nowhere; audience checking is not implemented |

**Example values that must not ship to production:**

- `issuers.jwtIgnoreExpiry: true` — expired tokens accepted indefinitely, silently.
- `timeLimit` mode `test` — search "now" pinned to 2020 — and its `25000`-day window;
  likewise `ApiServerVerticle.maxDaysSync: 25000`.
- `corsAllowedOrigin: ["*", ...]` — tighten to real origins.
- `keystorePassword: "password"` with the bundled example keystore.
- All blanked credentials (`dataBrokerPassword`, Elasticsearch `databasePassword`,
  `s3AccessKey`/`s3SecretKey`, `grpcClientSecret`) and blanked hosts must be filled per
  environment — the file will not boot as-is.

**Suspect structure — `issuers`.** As in the controlplane config, the map mixes per-issuer
objects with three scalar settings (`jwtIgnoreExpiry`, `jwtLeeway`, `jwksRefreshIntervalMs`) at the
same level. `JwksResolver` handles it, but any code iterating "one entry per issuer" would trip
over the scalars — they belong one level up.

**`configs/config-test.json` is a different era.** Its module list
(`DatabaseVerticle`, `AuthenticationVerticle`, `LatestVerticle`, `MeteringVerticle`, …) predates
the `dx-common` refactor and matches no class in the current tree. Keep only as a historical
reference or delete.

## 5. Submission checklist

- [x] Every leaf field in `configs/config-dev.json` has a block; the four `modules[]`
      scaffolding keys are documented once as a pattern plus the per-module `required` table
      in §1.
- [x] Every credential documents its privileges and origin — RabbitMQ (administrator tag),
      Elasticsearch, S3, Keycloak gRPC client, keystore.
- [x] Cross-service fields are flagged (`controlplaneHost`/`controlplaneGrpcPort`,
      `grpcClientId` ↔ controlplane allow-list, `appIdRevokeExchange`, `controlPlaneDomain`,
      issuer entries, `tenantPrefix`, advertised AMQP endpoint).
- [x] Each field states its failure mode.
- [x] Example file synced to the working `configs/config-dev.json` (dev), credentials blanked;
      remaining shape issues called out in §4.