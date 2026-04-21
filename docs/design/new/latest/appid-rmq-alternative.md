# AppId Verification — RMQ Request-Reply Alternative

**Feature:** Alternative to gRPC for AppId verification using RabbitMQ request-reply  
**Author:** Ankit Singh  
**Date:** 2026-04-09  
**Status:** Alternative / Not chosen (gRPC preferred) — documented for reference

---

## Why This Was Considered

The primary design (`appid-auth-via-dx-common.md`) uses gRPC for AppId verification between dataplane and controlplane. The gRPC channel uses plaintext in dev (`usePlaintext()`), and production TLS configuration (OQ3) requires knowing the network topology (same cluster vs cross-cluster).

If the network topology is cross-cluster or TLS management is complex, RMQ request-reply is a viable alternative — TLS is already handled at the RMQ broker level (AMQPS), and both services already have RMQ connections established.

---

## What This Is NOT

This is **not** the proactive cache-warming approach (which was rejected — see below).

This is **request-reply over RMQ**, triggered on cache miss, with `appId + appSecret` in the message. The secret is still validated by the controlplane per cache miss — functionally equivalent to gRPC.

### Why Proactive Cache Warming Was Rejected

If controlplane pre-publishes AppId metadata to RMQ on registration, the cache is populated before any request arrives. This means:
- An attacker knowing the AppId UUID (but not the secret) can hit the pre-populated cache and get authenticated
- The secret is **never validated** at the dataplane

The request-reply approach does not have this problem.

---

## Flow

```
Request arrives at dataplane with X-App-Id + X-App-Secret
       ↓
AppIdAuthHandler — cache miss
       ↓
Publish to RMQ: exchange=dx.appid, routingKey=appid.verify
Message: { appId, appSecret, replyTo: <temp-queue>, correlationId: <uuid> }
       ↓
Controlplane RMQ consumer receives message
  - Validates appId + appSecret (same logic as AppIdVerificationGrpcService)
  - Publishes reply to replyTo queue with correlationId
  - Reply: { success, errorCode, principal: { roles, scopes, entityMetadataMap, expiresAt } }
       ↓
Dataplane RMQ reply consumer receives reply (matched by correlationId)
  - On success: cache principal, populateContext(), ctx.next()
  - On failure: ctx.fail(401/403)
```

---

## Key Differences vs gRPC

| Aspect | gRPC | RMQ Request-Reply |
|--------|------|-------------------|
| TLS | Must configure separately per environment | Already handled at broker (AMQPS) |
| Latency | Lower (direct connection) | Higher (broker in the middle) |
| Pattern fit | New infrastructure in both services | Already in both services |
| Complexity | Proto stubs, channel management | Correlation IDs, reply queues, timeout handling |
| Failure mode | gRPC channel error → fail fast | Broker unavailable → fail; reply timeout → need explicit timeout handling |
| Existing usage | Not currently used | Dataplane already uses RMQ for auditing |

---

## Additional Pieces Needed (vs gRPC)

- **Dataplane**: RMQ publisher for verification requests + reply consumer with correlation ID matching + explicit timeout (e.g. 5s) to avoid hanging requests
- **Controlplane**: New RMQ consumer for `appid.verify` routing key + reply publisher
- **No proto files, no gRPC stubs, no `ManagedChannel`**

---

## When to Choose This Over gRPC

- Controlplane and dataplane are on **separate clusters** and cross-cluster gRPC TLS is complex to manage
- The team wants to stay within the existing RMQ-based inter-service communication pattern
- gRPC infrastructure is not already in place

---

## Decision

**gRPC is the chosen approach** for the primary design. This document is kept as a reference if OQ3 (production TLS) becomes a blocker.
