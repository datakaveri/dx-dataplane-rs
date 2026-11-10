# How to Create AppId and AppSecret

This guide explains how to register an AppId and AppSecret via the dx-controlplane HTTP API.
You need these credentials before running the AppId auth end-to-end tests.

---

## Prerequisites

- dx-controlplane is running on `http://localhost:8080`
- You have a **consumer JWT** (Keycloak or controlplane-issued token)
- You know the **entity UUID** (Elasticsearch resource item UUID) you want to query via AppId

---

## Step 1 — Get a Consumer JWT

If you already have a valid JWT from Postman or a previous session, skip to Step 2.

Otherwise, get one from the controlplane token endpoint:

```bash
curl -X POST http://localhost:8080/iudx/v2/auth/token \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "<your-keycloak-client-id>",
    "clientSecret": "<your-keycloak-client-secret>",
    "itemId": "<resource-item-uuid>",
    "itemType": "resource"
  }'
```

Copy the `accessToken` value from the response — that is your `CONSUMER_JWT`.

---

## Step 2 — Create the AppId

> **Important:** You must include `roles` with a `data_access` constraint listing the specific
> entity UUID you want to query. A wildcard app (body with only `expiry_at` and no `roles`) will
> NOT work with the dataplane — the gRPC service only populates `entityMetadataMap` when it finds
> a `data_access` constraint with a valid UUID `entity_id`. Without that, the dataplane returns 403.

Set your variables:

```bash
CONSUMER_JWT="<your-consumer-jwt>"
ENTITY_ID="<uuid-of-the-resource-you-want-to-query>"
CONTROLPLANE_HOST="http://localhost:8080"
```

Create the app:

```bash
curl -X POST "${CONTROLPLANE_HOST}/iudx/v2/auth/app" \
  -H "Authorization: Bearer ${CONSUMER_JWT}" \
  -H "Content-Type: application/json" \
  -d '{
    "expiry_at": "2027-12-31T23:59:59",
    "roles": [
      {
        "role": "consumer",
        "constraints": [
          {
            "scope": "data_access",
            "entity_type": "RESOURCE",
            "entity_id": ["'"${ENTITY_ID}"'"],
            "expiry_at": "2027-12-31T23:59:59"
          }
        ]
      }
    ]
  }'
```

### Expected response

```json
{
  "type": "urn:dx:ControlPlane:success",
  "title": "Success",
  "result": {
    "appId": "210e92ee-ab93-4593-a035-60e2620a8eb8",
    "userId": "6effed2b-999c-44da-987a-f56a3aba96c2",
    "expiryAt": "2027-12-31T23:59:59",
    "status": "active",
    "createdAt": "2026-04-16T10:00:00.000000",
    "app_secret": "3f8a1c2d9e4b7f6a..."
  }
}
```

**Save `appId` and `app_secret` immediately. The plain-text secret is returned only once
and cannot be retrieved again.**

| Variable | Where to get it |
|---|---|
| `APP_ID` | `result.appId` from response |
| `APP_SECRET` | `result.app_secret` from response |
| `ENTITY_ID` | same UUID you used in the request body |

---

## Step 3 — Verify in the Database

Confirm the rows were inserted correctly:

```sql
\c iudx_db
SET search_path TO aaa, public;

SELECT ac.app_id, ac.status, ac.expiry_at, c.scope, c.entity_id
FROM app_credentials ac
JOIN app_constraints c ON ac.app_id = c.app_id
WHERE ac.app_id = '<APP_ID>';
```

Expected output:

```
app_id  | status | expiry_at           | scope       | entity_id
--------+--------+---------------------+-------------+----------------------------------
<uuid>  | active | 2027-12-31 23:59:59 | data_access | <ENTITY_ID>
```

If `scope` is `*` and `entity_id` is `*`, the app was created as a wildcard (no `roles` in the
request body). Delete it and re-create with explicit constraints (Step 2).

---

## Step 4 — List Your Apps (to check appIds later)

The secret is never shown again after creation. You can list your apps to retrieve the `appId`:

```bash
curl -X GET "${CONTROLPLANE_HOST}/iudx/v2/auth/app" \
  -H "Authorization: Bearer ${CONSUMER_JWT}"
```

---

## Step 5 — Delete an App (if needed)

```bash
APP_ID="<uuid>"

curl -X DELETE "${CONTROLPLANE_HOST}/iudx/v2/auth/app/${APP_ID}" \
  -H "Authorization: Bearer ${CONSUMER_JWT}"
# Expected: 204 No Content
```

---

## What's Next

Once you have `APP_ID`, `APP_SECRET`, and `ENTITY_ID`, go to
[appid-auth-e2e-testing.md](appid-auth-e2e-testing.md) and start from **Step 3**
(test gRPC with grpcurl) using these values.
