from docx import Document
from docx.shared import Pt, RGBColor, Inches
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
import os

doc = Document()
style = doc.styles['Normal']
style.font.name = 'Calibri'
style.font.size = Pt(11)


def code(doc, text):
    p = doc.add_paragraph()
    r = p.add_run(text)
    r.font.name = 'Consolas'
    r.font.size = Pt(9)


def tbl(doc, headers, rows):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = 'Light Grid Accent 1'
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, h in enumerate(headers):
        t.rows[0].cells[i].text = h
    for rd in rows:
        cells = t.add_row().cells
        for i, v in enumerate(rd):
            cells[i].text = v


def bold_para(doc, b, n):
    p = doc.add_paragraph()
    p.add_run(b).bold = True
    p.add_run(n)


def bullets(doc, items):
    for i in items:
        doc.add_paragraph(i, style='List Bullet')


def numbered(doc, items):
    for i, s in enumerate(items, 1):
        doc.add_paragraph(f'{i}. {s}')


def question_box(doc, question, context=''):
    """Add a highlighted question block for manager review."""
    p = doc.add_paragraph()
    r = p.add_run('[QUESTION] ')
    r.bold = True
    r.font.color.rgb = RGBColor(0xC0, 0x39, 0x2B)  # Red
    r2 = p.add_run(question)
    r2.bold = True
    if context:
        p2 = doc.add_paragraph()
        r3 = p2.add_run('Context: ')
        r3.italic = True
        p2.add_run(context)


def challenge_box(doc, title, description):
    """Add a highlighted challenge block."""
    p = doc.add_paragraph()
    r = p.add_run('[CHALLENGE] ')
    r.bold = True
    r.font.color.rgb = RGBColor(0xE6, 0x7E, 0x22)  # Orange
    r2 = p.add_run(title)
    r2.bold = True
    doc.add_paragraph(description)


# ═══════════════════════════════════════════════════
# TITLE PAGE
# ═══════════════════════════════════════════════════
doc.add_heading('Design Document: Dual Authentication Support', level=0)
doc.add_heading('JWT + AppId for DX Dataplane & Controlplane', level=2)

doc.add_paragraph('')
p = doc.add_paragraph()
p.add_run('Version 3.0 (Deep Implementation Guide)').bold = True
doc.add_paragraph('Date: March 2026')
doc.add_paragraph('Scope: dx-dataplane-rs + dx-controlplane')
doc.add_paragraph('Status: Awaiting Manager Review')

doc.add_paragraph('')
doc.add_paragraph('')

# Legend
doc.add_heading('Document Legend', level=2)
p = doc.add_paragraph()
r = p.add_run('[QUESTION] ')
r.bold = True
r.font.color.rgb = RGBColor(0xC0, 0x39, 0x2B)
p.add_run('Items marked in RED require manager decision/clarification before implementation can proceed.')

p = doc.add_paragraph()
r = p.add_run('[CHALLENGE] ')
r.bold = True
r.font.color.rgb = RGBColor(0xE6, 0x7E, 0x22)
p.add_run('Items marked in ORANGE are technical challenges identified during analysis.')

doc.add_page_break()

# TOC
doc.add_heading('Table of Contents', level=1)
for item in [
    '1. Problem Statement',
    '2. Current Architecture Analysis',
    '3. Proposed Approaches & Recommendation',
    '4. Open Questions for Manager (MUST READ)',
    '5. Identified Challenges & Risks',
    '6. Controlplane: Implementation Details',
    '7. Dataplane: Implementation Details',
    '8. Data Flow: Handler-by-Handler Walkthrough',
    '9. Exact Code Changes Per File',
    '10. Security Considerations',
    '11. Edge Cases & Caching Strategy',
    '12. Configuration & Dependencies',
    '13. Implementation Order & Phasing',
    '14. Files Summary',
]:
    doc.add_paragraph(item, style='List Number')

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 1. PROBLEM STATEMENT
# ═══════════════════════════════════════════════════
doc.add_heading('1. Problem Statement', level=1)
doc.add_paragraph(
    'Currently, all dataplane APIs authenticate requests exclusively via JWT Bearer tokens '
    '(Keycloak-issued). We need to also support AppId-based authentication so that:')
bullets(doc, [
    'Machine-to-machine integrations can use a simple app identifier instead of managing JWT lifecycles.',
    'The dataplane can accept either authentication method on any protected endpoint.',
    'Clients don\'t need to handle token refresh - they just send the appId every time.',
    'AppId permissions can be updated in real-time without re-issuing tokens.',
])

doc.add_paragraph('')
bold_para(doc, 'Updated Requirement from Manager: ',
    'Only appId will be sent by the client (no appSecret). This changes the security model - '
    'the appId becomes a bearer credential similar to an API key.')

# ═══════════════════════════════════════════════════
# 2. CURRENT ARCHITECTURE
# ═══════════════════════════════════════════════════
doc.add_heading('2. Current Architecture Analysis', level=1)

doc.add_heading('2.1 Current Handler Chain (JWT Only)', level=2)
doc.add_paragraph('Every protected route follows this exact handler chain:')
code(doc,
    'builder.operation(OPERATION_NAME)\n'
    '    .handler(auditingHandler::handleApiAudit)              // 1. Audit\n'
    '    .handler(getIdFromPathHandler)                         // 2. Extract resource ID\n'
    '    .handler(AuthorizationHandler.forRoles(...))           // 3. JWT + role check\n'
    '    .handler(itemAccessApplicableFilterHandler)            // 4. Access info from controlplane\n'
    '    .handler(idValidation)                                 // 5. ID match check\n'
    '    .handler(this::handleActualRequest);                   // 6. Business logic')

doc.add_heading('2.2 JwtData Record (Core Auth Data Structure)', level=2)
code(doc,
    'public record JwtData(\n'
    '    String accessToken,    // Raw JWT string\n'
    '    String sub,            // Subject - user UUID\n'
    '    String iss,            // Issuer (Keycloak)\n'
    '    String aud,            // Audience\n'
    '    Long exp,              // Expiration timestamp\n'
    '    Long iat,              // Issued-at timestamp\n'
    '    String iid,            // Item ID - "provider:resourceId"\n'
    '    String role,           // "consumer", "provider", "delegate"\n'
    '    JsonObject cons,       // Constraints (allowedAttributes, access)\n'
    '    String drl,            // Delegated role\n'
    '    String did,            // Delegator ID\n'
    '    String expiry          // Expiry string\n'
    ')')

doc.add_paragraph('')
doc.add_heading('Which handlers use which JwtData fields:', level=3)
tbl(doc, ['JwtData Field', 'Used By', 'Purpose'],
    [('iid', 'AuthorizationServiceImpl', 'Split on ":" to get resource ID, match against requested resource'),
     ('role', 'AuthorizationHandler, ProviderValidationHandler', 'Check consumer/provider/delegate role'),
     ('sub', 'ProviderValidationHandler', 'Match user UUID against resource owner (provider role)'),
     ('did', 'ProviderValidationHandler', 'Match delegator UUID against resource owner (delegate role)'),
     ('drl', 'ProviderValidationHandler', 'Check delegated role has provider permission'),
     ('cons', 'ItemAccessApplicableFilterHandler', 'Extract allowedAttributes array')])

doc.add_heading('2.3 RoutingContext Data Flow', level=2)
doc.add_paragraph('Data stored in RoutingContext by upstream handlers, consumed by downstream:')
tbl(doc, ['RoutingContext Key', 'Set By', 'Consumed By'],
    [('jwtData', 'AuthorizationHandler', 'ResourcePolicyAuthorizationHandler, ProviderValidationHandler'),
     ('applicableFilter', 'ItemAccessApplicableFilterHandler', 'Business logic (query filtering)'),
     ('allowedAttributes', 'ItemAccessApplicableFilterHandler', 'Business logic (attribute filtering)'),
     ('accessPolicy', 'ItemAccessApplicableFilterHandler', 'IdValidation (skip check if OPEN)'),
     ('iid', 'ItemAccessApplicableFilterHandler', 'IdValidation (compare with requested ID)'),
     ('itemMetaData', 'ItemAccessApplicableFilterHandler', 'Business logic'),
     ('ownerUserId', 'ItemAccessApplicableFilterHandler', 'ProviderValidationHandler')])

doc.add_heading('2.4 Critical Finding: No Service-to-Service Authentication', level=2)
doc.add_paragraph(
    'The dataplane has NO service identity of its own. When calling controlplane endpoints, '
    'it simply forwards the user\'s JWT:')
code(doc,
    '// ItemAccessApplicableFilterHandlerNgsild.java\n'
    'getRequest.putHeader("Authorization", "Bearer " + bearerToken);  // user\'s JWT!\n\n'
    '// CheckItemAccessHandler.java\n'
    'getRequest.putHeader("Authorization", "Bearer " + bearerToken);  // user\'s JWT!')

doc.add_paragraph('')
challenge_box(doc,
    'No JWT available for downstream controlplane calls in appId flow',
    'When a client sends only an appId (no JWT), the downstream handlers '
    '(ItemAccessApplicableFilterHandler, CheckItemAccessHandler) cannot call controlplane '
    'because they have no Bearer token to forward. This is the fundamental architectural '
    'problem that drives our recommended approach.')

doc.add_heading('2.5 Existing Dual-Path Pattern', level=2)
doc.add_paragraph(
    'ItemAccessApplicableFilterHandlerNgsild already has a two-branch pattern. '
    'Both paths converge to store the SAME RoutingContext data:')
code(doc,
    'if (context.user().principal().containsKey("policies")) {\n'
    '    // PATH 1: JWT has policies embedded -> extract directly\n'
    '} else {\n'
    '    // PATH 2: No policies -> call controlplane with Bearer token\n'
    '}\n'
    '// Both paths store: applicableFilter, allowedAttributes, accessPolicy, iid')
bold_para(doc, 'Key insight: ',
    'We will add a PATH 3 for appId auth that also converges to store the same data. '
    'This means IdValidation and all business logic handlers need zero changes.')

doc.add_heading('2.6 AppId Database Schema (Controlplane)', level=2)
tbl(doc, ['Table', 'Key Columns', 'Purpose'],
    [('aaa.app_credentials', 'app_id (UUID PK), user_id, app_secret_hash, expiry_at, status, role, revoked_at', 'Stores app credentials with SHA-512 hashed secret'),
     ('app_constraints', 'app_id (FK), scope, entity_type, entity_id, user_id', 'Defines what each appId can access')])

doc.add_paragraph('')
doc.add_heading('Scope-to-Role Mapping:', level=3)
tbl(doc, ['Scope', 'Maps to Role(s)'],
    [('*', 'All user roles + all scopes'),
     ('data_access', 'consumer (requires item validation)'),
     ('asset_management', 'provider'),
     ('user_management', 'org_admin, consumer'),
     ('compute_management', 'compute')])

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 3. APPROACHES
# ═══════════════════════════════════════════════════
doc.add_heading('3. Proposed Approaches & Recommendation', level=1)

doc.add_heading('Approach A: Token Exchange (appId -> controlplane -> JWT)', level=2)
doc.add_paragraph(
    'Dataplane sends appId to controlplane, gets a JWT back, uses JWT for downstream calls.')
tbl(doc, ['Pros', 'Cons'],
    [('Downstream handlers get JWT to forward - minimal code changes', 'JWT generation overhead for every request'),
     ('Existing auth chain works mostly unchanged', 'Essentially duplicates /app/token minus secret'),
     ('', 'Still requires a new controlplane endpoint')])

doc.add_heading('Approach B: Rich Verification (appId -> controlplane -> full auth context) - RECOMMENDED', level=2)
doc.add_paragraph(
    'Dataplane sends appId to controlplane. Controlplane returns ALL authorization data in one response. '
    'Dataplane uses this data directly - no JWT needed, no further controlplane calls needed.')
tbl(doc, ['Pros', 'Cons'],
    [('Single controlplane call per request (vs 2-3 in JWT flow)', 'New rich endpoint needed in controlplane'),
     ('No JWT generation/signing overhead', 'Dataplane must handle dual auth context (JWT + AppAuthContext)'),
     ('Solves the "no token for downstream calls" problem completely', 'Cannot validate offline (needs controlplane reachable)'),
     ('Instant revocation (no JWT expiry wait)', ''),
     ('Live constraint updates (no token re-issue)', ''),
     ('Pre-fetches ALL needed data in one shot', '')])

doc.add_heading('Approach C: Client-Side Token Exchange', level=2)
doc.add_paragraph(
    'Client calls controlplane first to get JWT, then calls dataplane with JWT. '
    'Zero dataplane changes but pushes complexity to every client and contradicts appId-only requirement.')

doc.add_paragraph('')
bold_para(doc, 'RECOMMENDATION: ',
    'Approach B (Rich Verification). It solves all architectural problems cleanly, reduces '
    'controlplane calls from 2-3 to 1 per request, and eliminates the downstream token problem.')

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 4. OPEN QUESTIONS FOR MANAGER *** CRITICAL SECTION ***
# ═══════════════════════════════════════════════════
doc.add_heading('4. Open Questions for Manager', level=1)
p = doc.add_paragraph()
r = p.add_run('*** THIS SECTION REQUIRES YOUR INPUT BEFORE WE CAN PROCEED ***')
r.bold = True
r.font.color.rgb = RGBColor(0xC0, 0x39, 0x2B)

doc.add_paragraph('')

# Q1
doc.add_heading('Q1: Security Model - AppId Without Secret', level=2)
question_box(doc,
    'Is it acceptable that appId alone (no appSecret) acts as a bearer credential?',
    'Without a secret, anyone who obtains the appId UUID can make API calls. '
    'This is the same model as API keys (used by AWS, GCP, Stripe), but it\'s '
    'less secure than the current appId+appSecret model in controlplane. '
    'UUIDv4 has 122 bits of entropy so brute-force is impractical, but a leaked '
    'appId gives full access until revoked.')
doc.add_paragraph('')
doc.add_paragraph('Options to decide:')
bullets(doc, [
    'Option A: Accept appId-only (simpler for clients, API key pattern)',
    'Option B: Require appId + appSecret (more secure, but then we can use the existing /app/token endpoint)',
    'Option C: appId-only but with IP allowlisting (appId bound to specific client IPs)',
])

doc.add_paragraph('')

# Q2
doc.add_heading('Q2: Controlplane Endpoint - Who Builds It?', level=2)
question_box(doc,
    'Who will implement the new /app/verify endpoint in controlplane? Our team or the controlplane team?',
    'This feature requires changes in BOTH repositories. The controlplane needs a new '
    'POST /iudx/auth/v2/app/verify endpoint. If the controlplane team builds it, we need '
    'to agree on the response contract. If our team builds it, we need access and review from controlplane team.')
doc.add_paragraph('')
doc.add_paragraph('Related sub-questions:')
bullets(doc, [
    'Do we have commit access to dx-controlplane?',
    'Is there a code review/approval process for controlplane changes?',
    'Should the controlplane team be involved in designing the /app/verify response schema?',
])

doc.add_paragraph('')

# Q3
doc.add_heading('Q3: What Data Should /app/verify Return?', level=2)
question_box(doc,
    'Should the controlplane return resource-level details (accessPolicy, applicableFilters, allowedAttributes) or just constraints?',
    'We have two options for the /app/verify response richness:')
doc.add_paragraph('')
doc.add_paragraph('Option A: Rich response (recommended) - includes resource details:')
code(doc,
    '{\n'
    '  "userId": "...", "role": "consumer",\n'
    '  "constraints": [{scope, entityType, entityId}],\n'
    '  "accessibleResources": [\n'
    '    {\n'
    '      "entityId": "...",\n'
    '      "accessPolicy": "SECURE",\n'
    '      "applicableFilters": ["temporal", "attr"],\n'
    '      "allowedAttributes": ["speed", "temp"]\n'
    '    }\n'
    '  ]\n'
    '}')
doc.add_paragraph('Pro: Dataplane makes 1 controlplane call total. Con: More work for controlplane endpoint.')

doc.add_paragraph('')
doc.add_paragraph('Option B: Minimal response - just constraints:')
code(doc,
    '{\n'
    '  "userId": "...", "role": "consumer",\n'
    '  "constraints": [{scope, entityType, entityId}]\n'
    '}')
doc.add_paragraph(
    'Pro: Simpler controlplane endpoint. Con: Dataplane still needs to call controlplane '
    'for resource details (applicableFilters, accessPolicy) - partially defeats the purpose.')

doc.add_paragraph('')

# Q4
doc.add_heading('Q4: How to Secure the /app/verify Endpoint?', level=2)
question_box(doc,
    'How should the controlplane authenticate the dataplane when it calls /app/verify?',
    'Currently the dataplane has NO service identity - it just forwards the user\'s JWT. '
    'The /app/verify endpoint cannot be public because anyone with an appId could call it. '
    'We need to decide how to protect it.')
doc.add_paragraph('')
tbl(doc, ['Option', 'How It Works', 'Effort', 'Security Level'],
    [('A: Internal network only', '/app/verify accessible only on internal K8s network', 'Low (network config)', 'Medium - trusts network boundary'),
     ('B: Shared service key', 'Dataplane sends X-Service-Key header, controlplane validates', 'Medium (new config + middleware)', 'High - explicit service auth'),
     ('C: Dataplane Keycloak service account', 'Dataplane gets its own JWT from Keycloak', 'High (Keycloak config + code)', 'Highest - full service identity')])

doc.add_paragraph('')

# Q5
doc.add_heading('Q5: Cache Duration vs Revocation Speed', level=2)
question_box(doc,
    'What is the acceptable delay between revoking an appId and it actually stopping to work?',
    'To reduce controlplane load, we plan to cache /app/verify responses in the dataplane. '
    'With a 5-minute cache TTL, a revoked appId could continue working for up to 5 minutes. '
    'We need to know what\'s acceptable.')
doc.add_paragraph('')
tbl(doc, ['Cache TTL', 'Revocation Delay', 'Controlplane Load'],
    [('No cache', 'Instant', 'High - every request calls controlplane'),
     ('30 seconds', '30 seconds max', 'Medium-high'),
     ('5 minutes (recommended)', '5 minutes max', 'Low'),
     ('15 minutes', '15 minutes max', 'Very low')])

doc.add_paragraph('')

# Q6
doc.add_heading('Q6: Which APIs Should Support AppId Auth?', level=2)
question_box(doc,
    'Should ALL dataplane APIs support appId, or only specific ones?',
    'Currently we plan to add appId support to all protected endpoints. But some endpoints '
    'like data publish (provider operations) may not make sense with appId auth, since '
    'appIds are typically created for consumer-type access.')
doc.add_paragraph('')
doc.add_paragraph('Options:')
bullets(doc, [
    'All protected endpoints (simpler implementation, consistent behavior)',
    'Only consumer endpoints (GET /entities, /temporal/entities, /download) - exclude provider operations',
    'Configurable per-endpoint (most flexible, most complex)',
])

doc.add_paragraph('')

# Q7
doc.add_heading('Q7: Wildcard Constraints', level=2)
question_box(doc,
    'How should we handle wildcard constraints (entityId: "*") in app_constraints?',
    'Some appIds have wildcard constraints that allow access to ALL resources. '
    'In the JWT flow, resource access is controlled by the iid claim in the token. '
    'With wildcards, the appId effectively has unrestricted access within its scope.')
doc.add_paragraph('')
doc.add_paragraph('Options:')
bullets(doc, [
    'Allow wildcards - appId can access any resource within its scope',
    'Disallow wildcards for appId-only auth - require explicit resource constraints',
    'Allow wildcards but add rate limiting and enhanced audit logging',
])

doc.add_paragraph('')

# Q8
doc.add_heading('Q8: Timeline and Priority', level=2)
question_box(doc,
    'What is the expected timeline? Should we implement controlplane and dataplane in parallel or sequentially?',
    'We recommend Phase 1 (controlplane) first, then Phase 2 (dataplane). '
    'But if timeline is tight, both can be developed in parallel if the /app/verify '
    'response contract is agreed upon upfront.')

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 5. CHALLENGES & RISKS
# ═══════════════════════════════════════════════════
doc.add_heading('5. Identified Challenges & Risks', level=1)

# C1
doc.add_heading('5.1 Architectural Challenges', level=2)

challenge_box(doc,
    'Challenge 1: No Service-to-Service Authentication Exists',
    'The dataplane currently has no way to authenticate itself to the controlplane. '
    'All controlplane calls use the user\'s JWT. For the appId flow, there is no user JWT. '
    'The new /app/verify endpoint must either be network-protected (internal only) or we '
    'must introduce a service-to-service authentication mechanism for the first time. '
    'This is a precedent-setting architectural decision.')

doc.add_paragraph('')

challenge_box(doc,
    'Challenge 2: Dual Auth Context in Handler Chain',
    'Every downstream handler currently assumes JwtData is available in RoutingContext. '
    'For appId auth, we introduce AppAuthContext instead. Every handler that reads JwtData '
    'needs a dual-path: "if JWT auth, use JwtData; if appId auth, use AppAuthContext." '
    'This adds complexity to 5 handler classes. If not done carefully, it could introduce '
    'bugs where one path is tested but the other isn\'t.')

doc.add_paragraph('')

challenge_box(doc,
    'Challenge 3: AuthorizationHandler Lives in dx-common',
    'AuthorizationHandler is in the shared dx-common library, not in dx-dataplane-rs. '
    'Modifying it to support appId auth affects ALL services that use dx-common '
    '(not just the dataplane). We need to ensure the change is backward-compatible '
    'or make the appId branch opt-in.')

doc.add_paragraph('')

challenge_box(doc,
    'Challenge 4: Controlplane Response Must Include Resource Details',
    'For the rich verification approach, the controlplane\'s /app/verify endpoint must '
    'return resource-level details (accessPolicy, applicableFilters, allowedAttributes) '
    'that it currently only provides through separate endpoints. This means the controlplane '
    'must aggregate data from multiple sources (app_credentials, app_constraints, and the '
    'catalogue/item service) into a single response. This is more complex than a simple '
    'validation endpoint.')

doc.add_paragraph('')

doc.add_heading('5.2 Security Challenges', level=2)

challenge_box(doc,
    'Challenge 5: AppId as Bearer Credential (No Secret)',
    'Without appSecret, the appId UUID is the only credential. It must be treated as '
    'sensitive as a password. However, UUIDs can appear in logs, error messages, URLs, '
    'and browser dev tools. We need strict guidelines to never log appId values and '
    'ensure HTTPS everywhere.')

doc.add_paragraph('')

challenge_box(doc,
    'Challenge 6: Cache Staleness After Revocation',
    'If an appId is revoked in controlplane but the dataplane has it cached, requests '
    'will continue to succeed until the cache expires. For security-sensitive use cases, '
    'this may not be acceptable. A pub/sub mechanism for cache invalidation adds significant '
    'infrastructure complexity.')

doc.add_paragraph('')

doc.add_heading('5.3 Operational Challenges', level=2)

challenge_box(doc,
    'Challenge 7: Cross-Repository Coordination',
    'This feature requires synchronized changes in two repositories (dx-controlplane and '
    'dx-dataplane-rs). The controlplane endpoint must be deployed before the dataplane '
    'can use it. This creates a deployment dependency and requires coordination between '
    'teams if different teams own these repos.')

doc.add_paragraph('')

challenge_box(doc,
    'Challenge 8: Testing Complexity',
    'Integration testing requires both services running. Unit testing each handler\'s '
    'dual-path logic doubles the test surface area. We need test coverage for: '
    'JWT-only requests (regression), appId-only requests, requests with both credentials, '
    'expired appId, revoked appId, wildcard constraints, cache hits, cache misses, '
    'controlplane down, etc.')

doc.add_paragraph('')

challenge_box(doc,
    'Challenge 9: Existing /app/token vs New /app/verify',
    'The controlplane already has POST /iudx/auth/v2/app/token which takes appId + appSecret '
    'and returns a JWT. The new /app/verify is similar but: (a) doesn\'t require appSecret, '
    '(b) returns raw auth data instead of JWT, (c) returns richer data including resource details. '
    'We need to ensure these endpoints don\'t create confusion and are well-documented.')

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 6. CONTROLPLANE IMPLEMENTATION
# ═══════════════════════════════════════════════════
doc.add_heading('6. Controlplane: Implementation Details', level=1)

doc.add_heading('6.1 New Endpoint: POST /iudx/auth/v2/app/verify', level=2)

doc.add_heading('Request:', level=3)
code(doc,
    'POST /iudx/auth/v2/app/verify\n'
    'Content-Type: application/json\n'
    'X-Service-Key: <optional-service-key>\n\n'
    '{\n'
    '  "appId": "550e8400-e29b-41d4-a716-446655440000"\n'
    '}')

doc.add_heading('Success Response (200):', level=3)
code(doc,
    '{\n'
    '  "type": "urn:dx:controlPanel:success",\n'
    '  "title": "Success",\n'
    '  "result": {\n'
    '    "appId": "550e8400-e29b-41d4-a716-446655440000",\n'
    '    "userId": "user-uuid",\n'
    '    "role": "consumer",\n'
    '    "status": "active",\n'
    '    "expiresAt": "2026-06-18T00:00:00Z",\n'
    '    "constraints": [\n'
    '      {\n'
    '        "scope": "data_access",\n'
    '        "entityType": "resource",\n'
    '        "entityId": "b58da193-23d9-43eb-b98a-123abc"\n'
    '      }\n'
    '    ],\n'
    '    "accessibleResources": [\n'
    '      {\n'
    '        "entityId": "b58da193-23d9-43eb-b98a-123abc",\n'
    '        "accessPolicy": "SECURE",\n'
    '        "applicableFilters": ["temporal", "attr", "geo"],\n'
    '        "allowedAttributes": ["speed", "temperature"],\n'
    '        "resourceServerUrl": "rs.iudx.io",\n'
    '        "ownerUserId": "owner-uuid",\n'
    '        "type": "adex:Resource"\n'
    '      }\n'
    '    ]\n'
    '  }\n'
    '}')

doc.add_heading('Error Responses:', level=3)
tbl(doc, ['Status', 'Meaning'],
    [('404', 'appId not found'),
     ('401', 'appId expired'),
     ('403', 'appId revoked or disabled')])

doc.add_heading('6.2 Validation Logic (AppVerifyServiceImpl)', level=2)
doc.add_paragraph('Reuses validation from AppTokenServiceImpl.validateApp() minus secret check:')
numbered(doc, [
    'Look up appId in aaa.app_credentials table.',
    'Check app exists (else 404).',
    'Check revoked_at is null (else 403).',
    'Check status == "active" (else 403).',
    'Check expiry_at > now() using Asia/Kolkata timezone (else 401).',
    'NO SECRET CHECK - this is the key difference from /app/token.',
    'Fetch all constraints from app_constraints table.',
    'For each data_access constraint with non-wildcard entityId: fetch resource details from ItemService.',
    'Build and return aggregated response.',
])

doc.add_heading('6.3 Controlplane Classes', level=2)
tbl(doc, ['#', 'Class', 'Action', 'Description'],
    [('1', 'AppVerifyController.java', 'NEW', 'Registers POST /app/verify, extracts appId, delegates to service'),
     ('2', 'AppVerifyService.java', 'NEW', 'Interface: Future<JsonObject> verify(String appId)'),
     ('3', 'AppVerifyServiceImpl.java', 'NEW', 'Validate + fetch constraints + fetch resources + build response'),
     ('4', 'AppVerifyControllerFactory.java', 'NEW', 'Wire DAOs, ItemService -> Service -> Controller'),
     ('5', 'AppCredentialsDAO', 'REUSE', 'getById(appId) - already exists, no changes'),
     ('6', 'AppConstraintsDAO', 'REUSE', 'getAllWithFilters() - already exists, no changes'),
     ('7', 'ControllerFactory.java', 'MODIFY', 'Add new controller to registration list'),
     ('8', 'app.yaml (OpenAPI)', 'MODIFY', 'Add endpoint definition')])

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 7. DATAPLANE IMPLEMENTATION
# ═══════════════════════════════════════════════════
doc.add_heading('7. Dataplane: Implementation Details', level=1)

doc.add_heading('7.1 AppAuthContext (Parallel to JwtData)', level=2)
doc.add_paragraph('Every field maps to a specific downstream handler need:')
tbl(doc, ['AppAuthContext Field', 'Equivalent JwtData Field', 'Used By'],
    [('userId', 'sub', 'ProviderValidationHandler (owner check)'),
     ('role', 'role', 'AuthorizationHandler (role check)'),
     ('constraints[].entityId', 'iid (split on ":")', 'ResourcePolicyAuthorizationHandler'),
     ('accessibleResources[]', 'N/A (fetched via separate controlplane calls)', 'ItemAccessApplicableFilterHandler'),
     ('accessibleResources[].accessPolicy', 'N/A (fetched per request)', 'CheckItemAccessHandler, IdValidation'),
     ('accessibleResources[].allowedAttributes', 'cons.allowedAttributes', 'ItemAccessApplicableFilterHandler')])

doc.add_paragraph('')
code(doc,
    'public record AppAuthContext(\n'
    '    String appId,\n'
    '    String userId,           // maps to JwtData.sub()\n'
    '    String role,             // maps to JwtData.role()\n'
    '    String status,\n'
    '    String expiresAt,\n'
    '    List<AppConstraint> constraints,\n'
    '    List<AccessibleResource> accessibleResources\n'
    ') {\n'
    '    public Optional<AppConstraint> findConstraintForResource(String resId) { ... }\n'
    '    public Optional<AccessibleResource> findResource(String resId) { ... }\n'
    '    public boolean hasScope(String resId, String scope) { ... }\n'
    '}\n\n'
    'public record AppConstraint(String scope, String entityType, String entityId) {}\n\n'
    'public record AccessibleResource(\n'
    '    String entityId, String accessPolicy,\n'
    '    List<String> applicableFilters, List<String> allowedAttributes,\n'
    '    String resourceServerUrl, String ownerUserId, String type\n'
    ') {}')

doc.add_heading('7.2 AuthenticationHandler (NEW - First in Chain)', level=2)
code(doc,
    'public class AuthenticationHandler implements Handler<RoutingContext> {\n\n'
    '    private final AppCredentialAuthClient client;\n'
    '    private final Cache<String, AppAuthContext> cache;  // Caffeine\n\n'
    '    public void handle(RoutingContext context) {\n'
    '        String authHeader = context.request().getHeader("Authorization");\n'
    '        boolean hasBearer = authHeader != null\n'
    '            && authHeader.toLowerCase().startsWith("bearer ");\n'
    '        String appId = context.request().getHeader("appId");\n\n'
    '        if (hasBearer) {\n'
    '            context.put("authMethod", "jwt");\n'
    '            context.next();                    // JWT: pass through\n'
    '        } else if (appId != null && !appId.isBlank()) {\n'
    '            // Check cache, then call controlplane /app/verify\n'
    '            verifyAppId(context, appId);\n'
    '        } else {\n'
    '            context.response().setStatusCode(401).end(...);\n'
    '        }\n'
    '    }\n'
    '}')

doc.add_heading('7.3 Handler Modifications (Dual-Path Pattern)', level=2)
doc.add_paragraph('Every modified handler follows this pattern:')
code(doc,
    'public void handle(RoutingContext context) {\n'
    '    if (RsRoutingContextHelper.isAppIdAuth(context)) {\n'
    '        // NEW: AppId flow\n'
    '        AppAuthContext ctx = RsRoutingContextHelper.getAppAuthContext(context).get();\n'
    '        // ... use ctx instead of JwtData ...\n'
    '        return;\n'
    '    }\n\n'
    '    // EXISTING: JWT flow (completely unchanged)\n'
    '    // ... original code ...\n'
    '}')

doc.add_paragraph('')
doc.add_paragraph('Specific changes per handler:')
tbl(doc, ['Handler', 'AppId Branch Logic'],
    [('AuthorizationHandler', 'Check AppAuthContext.role() against allowed roles (skip JWT extraction)'),
     ('ItemAccessApplicableFilterHandler', 'Use AppAuthContext.findResource(itemId) to get applicableFilters, allowedAttributes, accessPolicy. Store in RoutingContext. NO controlplane call.'),
     ('CheckItemAccessHandler', 'Skip entirely - access already verified by /app/verify'),
     ('ResourcePolicyAuthorizationHandler', 'Check AppAuthContext.hasScope(resourceId, "data_access") instead of JWT iid match'),
     ('ProviderValidationHandler', 'Compare AppAuthContext.userId() with resource owner')])

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 8. DATA FLOW WALKTHROUGH
# ═══════════════════════════════════════════════════
doc.add_heading('8. Data Flow: Handler-by-Handler Walkthrough', level=1)

tbl(doc,
    ['Step', 'Handler', 'JWT Flow', 'AppId Flow'],
    [('1', 'AuditingHandler', 'Log request (unchanged)', 'Log request (unchanged)'),
     ('2', 'AuthenticationHandler\n(NEW)', 'Detect Bearer token.\nSet authMethod="jwt".\nPass through.',
      'Detect appId header.\nCall POST /app/verify.\nStore AppAuthContext.\nSet authMethod="appId".'),
     ('3', 'GetIdFromPath', 'Extract resource ID\n(unchanged)', 'Extract resource ID\n(unchanged)'),
     ('4', 'AuthorizationHandler\n(MODIFIED)', 'Extract JWT.\nValidate signature.\nCheck role.',
      'Get AppAuthContext.\nCheck role from ctx.\n(Skip JWT extraction)'),
     ('5', 'ItemAccessFilter\n(MODIFIED)',
      'PATH 1: JWT has policies\n-> use directly.\nPATH 2: Call controlplane\nwith Bearer token.',
      'NEW PATH 3:\nUse AppAuthContext\n.accessibleResources\ndirectly.\nNO controlplane call.'),
     ('6', 'IdValidation\n(UNCHANGED)', 'Check accessPolicy.\nIf OPEN -> pass.\nElse: id == iid.',
      'Same logic.\nSame RoutingContext keys.'),
     ('7', 'Business Logic\n(UNCHANGED)', 'Uses RoutingContext data', 'Same RoutingContext data')])

doc.add_paragraph('')
bold_para(doc, 'CONVERGENCE POINT: ',
    'After step 5, both flows have stored the SAME data in RoutingContext '
    '(applicableFilter, allowedAttributes, accessPolicy, iid). Steps 6 and 7 '
    'work identically for both flows with ZERO changes.')

doc.add_paragraph('')
doc.add_heading('Controlplane Calls Comparison:', level=2)
tbl(doc, ['Metric', 'JWT Flow', 'AppId Flow'],
    [('Controlplane calls per request', '2-3', '1'),
     ('Where calls happen', 'ItemAccessFilter + CheckItemAccess', 'AuthenticationHandler only'),
     ('Auth passed to controlplane', 'User\'s JWT Bearer token', 'appId in request body')])

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 9. CODE CHANGES
# ═══════════════════════════════════════════════════
doc.add_heading('9. Exact Code Changes Per File', level=1)

doc.add_heading('9.1 Controlplane Changes', level=2)
tbl(doc, ['File', 'Action', 'Change'],
    [('AppVerifyController.java', 'NEW', 'POST /app/verify endpoint'),
     ('AppVerifyService.java', 'NEW', 'Interface: verify(String appId)'),
     ('AppVerifyServiceImpl.java', 'NEW', 'Validate + fetch + build response'),
     ('AppVerifyControllerFactory.java', 'NEW', 'Wire dependencies'),
     ('ControllerFactory.java', 'MODIFY', 'Register new controller'),
     ('app.yaml', 'MODIFY', 'Add endpoint spec')])

doc.add_heading('9.2 Dataplane Changes', level=2)
tbl(doc, ['File', 'Action', 'Change'],
    [('AppAuthContext.java', 'NEW', 'Auth context record (parallel to JwtData)'),
     ('AppConstraint.java', 'NEW', 'Constraint record'),
     ('AccessibleResource.java', 'NEW', 'Resource details record'),
     ('AppCredentialAuthClient.java', 'NEW', 'HTTP client for /app/verify'),
     ('AuthenticationHandler.java', 'NEW', 'Detect JWT vs appId, cache, verify'),
     ('RsRoutingContextHelper.java', 'MODIFY', 'Add setAppAuthContext(), isAppIdAuth()'),
     ('ApiConstants.java', 'MODIFY', 'Add HEADER_APP_ID, update ALLOWED_HEADERS'),
     ('AuthorizationHandler (dx-common)', 'MODIFY', 'Add appId role check branch'),
     ('ItemAccessApplicableFilterHandler', 'MODIFY', 'Add Path 3: use AppAuthContext'),
     ('CheckItemAccessHandler', 'MODIFY', 'Add appId bypass (pre-verified)'),
     ('ResourcePolicyAuthorizationHandler', 'MODIFY', 'Add constraint-based check'),
     ('ProviderValidationHandler', 'MODIFY', 'Add appId owner validation'),
     ('LatestController.java', 'MODIFY', 'Add authHandler to chain'),
     ('DownloadController.java', 'MODIFY', 'Add authHandler to chain'),
     ('NGSILDSearchController.java', 'MODIFY', 'Add authHandler to chain'),
     ('security-schemes.yaml', 'MODIFY', 'Add appId scheme'),
     ('config-dev.json', 'MODIFY', 'Add verify endpoint + cache config'),
     ('pom.xml', 'MODIFY', 'Add Caffeine dependency')])

doc.add_heading('9.3 Updated Route Chain', level=2)
code(doc,
    '// BEFORE:\n'
    '.handler(auditingHandler::handleApiAudit)\n'
    '.handler(getIdFromPathHandler)\n'
    '.handler(AuthorizationHandler.forRoles(...))\n'
    '.handler(itemAccessApplicableFilterHandler)\n'
    '.handler(idValidation)\n'
    '.handler(this::handleActualRequest)\n\n'
    '// AFTER:\n'
    '.handler(authenticationHandler::handle)        // <-- NEW (first!)\n'
    '.handler(auditingHandler::handleApiAudit)\n'
    '.handler(getIdFromPathHandler)\n'
    '.handler(AuthorizationHandler.forRoles(...))\n'
    '.handler(itemAccessApplicableFilterHandler)\n'
    '.handler(idValidation)\n'
    '.handler(this::handleActualRequest)')

doc.add_heading('9.4 Files That Need NO Changes', level=2)
bullets(doc, [
    'IdValidation.java - uses RoutingContext keys, works for both flows',
    'Business logic handlers - uses same RoutingContext data',
    'Existing /app/token endpoint - unchanged',
    'Database schema - all tables already exist',
])

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 10. SECURITY
# ═══════════════════════════════════════════════════
doc.add_heading('10. Security Considerations', level=1)
tbl(doc, ['Risk', 'Impact', 'Mitigation'],
    [('AppId leaked', 'Unauthorized resource access', 'HTTPS only, never log appId, internal /app/verify'),
     ('UUID brute-force', 'Attacker guesses appId', 'UUIDv4 = 122 bits (impractical). Rate limit.'),
     ('No proof of possession', 'Can\'t prove ownership', 'Accepted trade-off (API key pattern)'),
     ('Replay attacks', 'Captured appId reused', 'HTTPS prevents sniffing. Short expiry.'),
     ('/app/verify public', 'Anyone verifies any appId', 'Internal network only')])

doc.add_paragraph('')
doc.add_heading('Recommended Controls:', level=2)
numbered(doc, [
    'HTTPS only - appId must never travel over plain HTTP.',
    'Internal endpoint - /app/verify accessible only within service mesh.',
    'Rate limiting - per-appId and per-IP on dataplane.',
    'Short expiry - encourage short-lived appIds.',
    'Audit logging - log all appId usage (but NOT the appId value itself).',
    'IP allowlisting (future) - restrict appId to specific IPs.',
])

# ═══════════════════════════════════════════════════
# 11. EDGE CASES & CACHING
# ═══════════════════════════════════════════════════
doc.add_heading('11. Edge Cases & Caching Strategy', level=1)
tbl(doc, ['Case', 'Behavior'],
    [('Both Bearer + appId headers', 'Bearer token takes priority (JWT flow)'),
     ('Controlplane unreachable', '503 Service Unavailable (not 401)'),
     ('AppId valid, resource not in constraints', '403 Forbidden'),
     ('Wildcard constraint (entityId: "*")', 'Allow access, no attribute restrictions'),
     ('AppId revoked while cached', 'Stale for up to cacheTTL (default 5 min)')])

doc.add_paragraph('')
doc.add_heading('Cache Configuration:', level=2)
tbl(doc, ['Parameter', 'Default', 'Configurable?'],
    [('Cache library', 'Caffeine', 'No'),
     ('Cache key', 'appId (UUID string)', 'No'),
     ('TTL', '5 minutes', 'Yes (cacheTtlSeconds)'),
     ('Max entries', '10,000', 'Yes (cacheMaxSize)'),
     ('Eviction', 'LRU', 'No')])

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 12. CONFIG
# ═══════════════════════════════════════════════════
doc.add_heading('12. Configuration & Dependencies', level=1)

doc.add_heading('Dataplane config-dev.json additions:', level=2)
code(doc,
    '{\n'
    '  "controlplane": {\n'
    '    "baseUrl": "https://v2.dev.controlplane.iudx.io",\n'
    '    "appVerifyEndpoint": "/iudx/auth/v2/app/verify"\n'
    '  },\n'
    '  "appAuth": {\n'
    '    "cacheEnabled": true,\n'
    '    "cacheTtlSeconds": 300,\n'
    '    "cacheMaxSize": 10000\n'
    '  }\n'
    '}')

doc.add_heading('New Maven dependency (pom.xml):', level=2)
code(doc,
    '<dependency>\n'
    '    <groupId>com.github.ben-manes.caffeine</groupId>\n'
    '    <artifactId>caffeine</artifactId>\n'
    '    <version>3.1.8</version>\n'
    '</dependency>')

# ═══════════════════════════════════════════════════
# 13. IMPLEMENTATION ORDER
# ═══════════════════════════════════════════════════
doc.add_heading('13. Implementation Order & Phasing', level=1)

doc.add_heading('Phase 1: Controlplane (Must deploy first)', level=2)
numbered(doc, [
    'Create AppVerifyService + AppVerifyServiceImpl.',
    'Create AppVerifyController with POST /app/verify.',
    'Create AppVerifyControllerFactory and register.',
    'Update OpenAPI spec (app.yaml).',
    'Write unit + integration tests.',
    'Deploy to dev environment.',
])

doc.add_heading('Phase 2: Dataplane (After controlplane is deployed)', level=2)
numbered(doc, [
    'Create AppAuthContext, AppConstraint, AccessibleResource records.',
    'Create AppCredentialAuthClient + AuthenticationHandler.',
    'Add new methods to RsRoutingContextHelper.',
    'Modify AuthorizationHandler (dx-common) - add appId branch.',
    'Modify ItemAccessApplicableFilterHandler - add Path 3.',
    'Modify CheckItemAccessHandler + ResourcePolicyAuthorizationHandler.',
    'Modify ProviderValidationHandler.',
    'Update controllers, ApiConstants, CORS, config.',
    'Update OpenAPI security schemes.',
    'Add Caffeine to pom.xml.',
    'Write unit + integration tests.',
])

bold_para(doc, 'Deployment dependency: ',
    'Phase 1 MUST be deployed before Phase 2 can be tested. '
    'Both phases can be DEVELOPED in parallel if the /app/verify response contract is agreed upfront.')

doc.add_page_break()

# ═══════════════════════════════════════════════════
# 14. FILES SUMMARY
# ═══════════════════════════════════════════════════
doc.add_heading('14. Files Summary', level=1)

doc.add_heading('Controlplane - New Files (4)', level=2)
bullets(doc, [
    'AppVerifyController.java',
    'AppVerifyService.java (interface)',
    'AppVerifyServiceImpl.java',
    'AppVerifyControllerFactory.java',
])

doc.add_heading('Controlplane - Modified Files (2)', level=2)
bullets(doc, [
    'ControllerFactory.java (register new controller)',
    'app.yaml (add endpoint definition)',
])

doc.add_heading('Dataplane - New Files (5)', level=2)
bullets(doc, [
    'AppAuthContext.java, AppConstraint.java, AccessibleResource.java (model records)',
    'AppCredentialAuthClient.java (HTTP client)',
    'AuthenticationHandler.java (dual-auth detection)',
])

doc.add_heading('Dataplane - Modified Files (13)', level=2)
bullets(doc, [
    'RsRoutingContextHelper.java',
    'ApiConstants.java',
    'AuthorizationHandler.java (dx-common)',
    'ResourcePolicyAuthorizationHandler.java',
    'ItemAccessApplicableFilterHandlerNgsild.java',
    'CheckItemAccessHandler.java',
    'ProviderValidationHandler.java',
    'LatestController.java, DownloadController.java, NGSILDSearchController.java',
    'security-schemes.yaml, config-dev.json, pom.xml',
])

doc.add_heading('Files Needing NO Changes', level=2)
bullets(doc, [
    'IdValidation.java',
    'All business logic handlers',
    'Existing /app/token endpoint',
    'Database schema (all tables exist)',
])

# ═══════════════════════════════════════════════════
# SUMMARY PAGE
# ═══════════════════════════════════════════════════
doc.add_page_break()
doc.add_heading('Summary: Key Decisions Needed', level=1)
doc.add_paragraph(
    'Before implementation can begin, the following decisions are needed:')
doc.add_paragraph('')

tbl(doc, ['#', 'Question', 'Options', 'Impact'],
    [('Q1', 'AppId without secret acceptable?', 'Accept / Require secret / Add IP allowlisting', 'Determines entire security model'),
     ('Q2', 'Who builds controlplane endpoint?', 'Our team / Controlplane team / Joint', 'Affects timeline and coordination'),
     ('Q3', 'Rich vs minimal /app/verify response?', 'Rich (recommended) / Minimal', 'Determines dataplane complexity'),
     ('Q4', 'How to secure /app/verify?', 'Internal network / Service key / Service account', 'First service-to-service auth decision'),
     ('Q5', 'Cache TTL vs revocation speed?', 'No cache / 30s / 5min / 15min', 'Security vs performance trade-off'),
     ('Q6', 'Which APIs support appId?', 'All / Consumer only / Configurable', 'Scope of dataplane changes'),
     ('Q7', 'Allow wildcard constraints?', 'Allow / Disallow / Allow with limits', 'Access control granularity'),
     ('Q8', 'Timeline and phasing?', 'Sequential / Parallel', 'Resource allocation')])

doc.add_paragraph('')
doc.add_paragraph('')

# Footer
p = doc.add_paragraph()
r = p.add_run('Version 3.0 | March 2026 | Prepared for Manager Review')
r.italic = True
p.alignment = WD_ALIGN_PARAGRAPH.RIGHT

p2 = doc.add_paragraph()
r2 = p2.add_run('Scope: dx-dataplane-rs + dx-controlplane')
r2.italic = True
p2.alignment = WD_ALIGN_PARAGRAPH.RIGHT

# Save
output_path = os.path.join(os.path.dirname(__file__), 'Dual_Auth_Support_Design_Document.docx')
doc.save(output_path)
print(f'Document saved to: {output_path}')
