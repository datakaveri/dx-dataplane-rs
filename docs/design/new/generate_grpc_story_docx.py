"""
Generate JIRA-style story DOCX for gRPC-Only Auth Design.
Run: python3 generate_grpc_story_docx.py
Output: grpc-only-auth-story.docx
"""

from docx import Document
from docx.shared import Pt, RGBColor, Inches, Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.style import WD_STYLE_TYPE
from docx.oxml.ns import qn
from docx.oxml import OxmlElement
import datetime

OUTPUT = "grpc-only-auth-story.docx"

# ─── Color palette ───────────────────────────────────────────────────────────
BLUE_DARK   = RGBColor(0x17, 0x48, 0x88)   # headings
BLUE_MID    = RGBColor(0x27, 0x6D, 0xB2)   # sub-headings
BLUE_LIGHT  = RGBColor(0xDF, 0xEA, 0xF8)   # header fill / label bg
ORANGE      = RGBColor(0xE6, 0x6C, 0x00)   # accent / label text
GREY_DARK   = RGBColor(0x3C, 0x3C, 0x3C)   # body text
GREY_LIGHT  = RGBColor(0xF5, 0xF5, 0xF5)   # alternate row fill
GREEN       = RGBColor(0x1A, 0x7A, 0x3C)   # in-scope
RED         = RGBColor(0xA0, 0x00, 0x00)   # out-of-scope / risk

# ─── Helpers ─────────────────────────────────────────────────────────────────

def set_cell_bg(cell, hex_color: str):
    """Set table cell background colour."""
    tc = cell._tc
    tcPr = tc.get_or_add_tcPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:val'), 'clear')
    shd.set(qn('w:color'), 'auto')
    shd.set(qn('w:fill'), hex_color)
    tcPr.append(shd)

def set_col_width(table, col_idx, width_cm):
    for row in table.rows:
        row.cells[col_idx].width = Cm(width_cm)

def add_h1(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(16)
    p.paragraph_format.space_after  = Pt(4)
    run = p.add_run(text)
    run.bold = True
    run.font.size = Pt(16)
    run.font.color.rgb = BLUE_DARK
    return p

def add_h2(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(12)
    p.paragraph_format.space_after  = Pt(2)
    run = p.add_run(text)
    run.bold = True
    run.font.size = Pt(13)
    run.font.color.rgb = BLUE_MID
    return p

def add_h3(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(8)
    p.paragraph_format.space_after  = Pt(1)
    run = p.add_run(text)
    run.bold = True
    run.font.size = Pt(11)
    run.font.color.rgb = GREY_DARK
    return p

def add_body(doc, text, indent=False):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(2)
    p.paragraph_format.space_after  = Pt(2)
    if indent:
        p.paragraph_format.left_indent = Inches(0.3)
    run = p.add_run(text)
    run.font.size = Pt(10.5)
    run.font.color.rgb = GREY_DARK
    return p

def add_bullet(doc, text, level=0, bold_prefix=None):
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.space_before = Pt(1)
    p.paragraph_format.space_after  = Pt(1)
    p.paragraph_format.left_indent  = Inches(0.3 + level * 0.25)
    if bold_prefix:
        r = p.add_run(bold_prefix + " ")
        r.bold = True
        r.font.size = Pt(10.5)
        r.font.color.rgb = GREY_DARK
    r = p.add_run(text)
    r.font.size = Pt(10.5)
    r.font.color.rgb = GREY_DARK
    return p

def add_numbered(doc, text, bold_prefix=None):
    p = doc.add_paragraph(style='List Number')
    p.paragraph_format.space_before = Pt(1)
    p.paragraph_format.space_after  = Pt(1)
    p.paragraph_format.left_indent  = Inches(0.3)
    if bold_prefix:
        r = p.add_run(bold_prefix + " ")
        r.bold = True
        r.font.size = Pt(10.5)
        r.font.color.rgb = GREY_DARK
    r = p.add_run(text)
    r.font.size = Pt(10.5)
    r.font.color.rgb = GREY_DARK
    return p

def add_divider(doc):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.space_after  = Pt(4)
    pPr = p._p.get_or_add_pPr()
    pBdr = OxmlElement('w:pBdr')
    bottom = OxmlElement('w:bottom')
    bottom.set(qn('w:val'), 'single')
    bottom.set(qn('w:sz'), '6')
    bottom.set(qn('w:space'), '1')
    bottom.set(qn('w:color'), '276DB2')
    pBdr.append(bottom)
    pPr.append(pBdr)
    return p

def add_label_value_table(doc, rows):
    """Two-column label/value table for the Jira metadata header."""
    tbl = doc.add_table(rows=len(rows), cols=2)
    tbl.style = 'Table Grid'
    for i, (label, value) in enumerate(rows):
        lc = tbl.rows[i].cells[0]
        vc = tbl.rows[i].cells[1]
        set_cell_bg(lc, 'DFEaf8')
        lc.paragraphs[0].clear()
        r = lc.paragraphs[0].add_run(label)
        r.bold = True
        r.font.size = Pt(9.5)
        r.font.color.rgb = BLUE_DARK
        vc.paragraphs[0].clear()
        r2 = vc.paragraphs[0].add_run(value)
        r2.font.size = Pt(9.5)
        r2.font.color.rgb = GREY_DARK
    set_col_width(tbl, 0, 4.5)
    set_col_width(tbl, 1, 12.0)
    return tbl

def add_ac_table(doc, items):
    """Acceptance criteria table with index + criterion + done columns."""
    tbl = doc.add_table(rows=1 + len(items), cols=3)
    tbl.style = 'Table Grid'
    # Header
    headers = ['#', 'Acceptance Criterion', 'Done']
    for j, h in enumerate(headers):
        c = tbl.rows[0].cells[j]
        set_cell_bg(c, '174888')
        c.paragraphs[0].clear()
        r = c.paragraphs[0].add_run(h)
        r.bold = True
        r.font.size = Pt(9.5)
        r.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
    # Rows
    for i, item in enumerate(items):
        row = tbl.rows[i + 1]
        bg = 'F5F5F5' if i % 2 == 0 else 'FFFFFF'
        row.cells[0].text = str(i + 1)
        row.cells[1].text = item
        row.cells[2].text = '☐'
        for c in row.cells:
            set_cell_bg(c, bg)
            for p in c.paragraphs:
                for run in p.runs:
                    run.font.size = Pt(9.5)
                    run.font.color.rgb = GREY_DARK
    set_col_width(tbl, 0, 0.8)
    set_col_width(tbl, 1, 14.2)
    set_col_width(tbl, 2, 1.5)
    return tbl

def add_three_col_table(doc, headers_list, rows_data, col_widths):
    n_cols = len(headers_list)
    tbl = doc.add_table(rows=1 + len(rows_data), cols=n_cols)
    tbl.style = 'Table Grid'
    for j, h in enumerate(headers_list):
        c = tbl.rows[0].cells[j]
        set_cell_bg(c, '174888')
        c.paragraphs[0].clear()
        r = c.paragraphs[0].add_run(h)
        r.bold = True
        r.font.size = Pt(9.5)
        r.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
    for i, row_data in enumerate(rows_data):
        row = tbl.rows[i + 1]
        bg = 'F5F5F5' if i % 2 == 0 else 'FFFFFF'
        for j, val in enumerate(row_data):
            row.cells[j].text = val
            set_cell_bg(row.cells[j], bg)
            for p in row.cells[j].paragraphs:
                for run in p.runs:
                    run.font.size = Pt(9.5)
                    run.font.color.rgb = GREY_DARK
    for j, w in enumerate(col_widths):
        set_col_width(tbl, j, w)
    return tbl

# ─── Document build ──────────────────────────────────────────────────────────

doc = Document()

# Page margins
for section in doc.sections:
    section.top_margin    = Cm(1.8)
    section.bottom_margin = Cm(1.8)
    section.left_margin   = Cm(2.0)
    section.right_margin  = Cm(2.0)

# Default paragraph font
style = doc.styles['Normal']
style.font.name = 'Calibri'
style.font.size = Pt(10.5)

# ════════════════════════════════════════════════════════════════════════════
# TITLE BLOCK
# ════════════════════════════════════════════════════════════════════════════
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.LEFT
r = p.add_run("FEATURE STORY")
r.bold = True
r.font.size = Pt(9)
r.font.color.rgb = ORANGE

p2 = doc.add_paragraph()
r2 = p2.add_run("gRPC-Only Authentication for DX Dataplane")
r2.bold = True
r2.font.size = Pt(20)
r2.font.color.rgb = BLUE_DARK

p3 = doc.add_paragraph()
r3 = p3.add_run(
    "AppId and JWT authentication over a pure gRPC stack — from external client calls "
    "to all internal dataplane-to-controlplane verification — replacing scattered HTTP "
    "calls with a single persistent gRPC channel."
)
r3.font.size = Pt(11)
r3.font.color.rgb = GREY_DARK
r3.italic = True

add_divider(doc)

# ════════════════════════════════════════════════════════════════════════════
# JIRA METADATA TABLE
# ════════════════════════════════════════════════════════════════════════════
today = datetime.date.today().strftime("%d %b %Y")
add_label_value_table(doc, [
    ("Story ID",          "TBD — assign in Jira"),
    ("Epic",              "DX Dataplane — External gRPC API"),
    ("Type",              "Feature / Technical Story"),
    ("Priority",          "High"),
    ("Reporter",          "Ankit Singh"),
    ("Assignee",          "TBD"),
    ("Target Release",    "TBD"),
    ("Created",           today),
    ("Version",           "1.0 — Draft for Manager Approval"),
    ("Related Docs",      "grpc-only-auth-design.md, app-key-access-design.md, grpc-external-auth-design.md"),
])
doc.add_paragraph()

# ════════════════════════════════════════════════════════════════════════════
# 1. USER STORY
# ════════════════════════════════════════════════════════════════════════════
add_h1(doc, "1. User Story")
add_divider(doc)

add_h2(doc, "As a platform engineer")
add_body(doc,
    "I want all authentication and authorisation checks between the dataplane and the "
    "controlplane to flow through a single gRPC channel, so that:")
add_bullet(doc, "External IoT scripts and applications can call DX dataplane APIs over gRPC "
           "(not just REST), using either an AppId credential or a JWT token.")
add_bullet(doc, "Every internal auth verification call — whether for AppId or JWT — uses a "
           "single Protobuf-typed gRPC RPC instead of ad-hoc HTTP calls with JSON payloads.")
add_bullet(doc, "There is one consistent internal protocol (gRPC) with one security model "
           "(network policy → mTLS) instead of a mix of HTTP and gRPC.")
add_bullet(doc, "The existing REST API on port 8443 continues to work unchanged, but its "
           "auth verification is also backed by the same gRPC clients.")

doc.add_paragraph()
add_h2(doc, "Problem Statement")
add_body(doc,
    "Today, all external clients reach the DX dataplane via HTTP/REST only. When the dataplane "
    "needs to verify a user's access rights, it makes an HTTP GET call to the controlplane "
    "(GET /iudx/v2/cat/item/access). There is no AppId authentication at all yet. "
    "As we add AppId support and an external gRPC interface, we have a design choice: "
    "do we introduce yet another HTTP endpoint on the controlplane for AppId verification, "
    "or do we adopt gRPC for all internal auth calls? This story adopts gRPC throughout — "
    "one protocol, one channel, three RPCs.")

# ════════════════════════════════════════════════════════════════════════════
# 2. BACKGROUND
# ════════════════════════════════════════════════════════════════════════════
add_divider(doc)
add_h1(doc, "2. Background and Current Architecture")
add_divider(doc)

add_h2(doc, "2.1 How the System Works Today")
add_body(doc,
    "The DX platform has exactly two servers: the Controlplane and the Dataplane. "
    "The Controlplane is the single authority for authentication, access control, and catalogue "
    "metadata. The Dataplane holds all time-series sensor data in Elasticsearch and serves it "
    "to external consumers.")

add_bullet(doc, "External clients call the Dataplane (not the Controlplane) for data queries. "
           "The Dataplane contacts the Controlplane to verify access rights.")
add_bullet(doc, "JWT tokens are validated locally on the Dataplane using JWKS (public key fetch "
           "from the Controlplane's Keycloak). This requires no round-trip for the signature check.")
add_bullet(doc, "If the JWT does not carry embedded policy data, the Dataplane calls "
           "GET /iudx/v2/cat/item/access on the Controlplane to fetch allowed query types, "
           "attributes, and access policy. This is called the 'slow path'.")
add_bullet(doc, "There is currently no AppId authentication. This story introduces it.")
add_bullet(doc, "All external clients today use REST over HTTPS (port 8443). "
           "There is no external gRPC interface.")

add_h2(doc, "2.2 What Authentication Context the Dataplane Needs")
add_body(doc,
    "For every data request, the Dataplane needs the following information before it can "
    "query Elasticsearch. Both AppId and JWT must ultimately produce the same set of values:")
add_bullet(doc, "User identity: a real user UUID (sub) and issuer, for audit logging.")
add_bullet(doc, "Role: consumer or delegate — required by the AuthorizationHandler.")
add_bullet(doc, "Access policy: OPEN, SECURE, or PII — controls whether expiry is checked.")
add_bullet(doc, "Allowed query types: TEMPORAL, ATTR, GEO — controls which filter types are permitted.")
add_bullet(doc, "Allowed attributes: a list of sensor attributes the user may see (empty = all).")
add_bullet(doc, "Policy ID: UUID of the governing policy, for audit records.")

add_h2(doc, "2.3 Dead Code — Not Part of the New Design")
add_body(doc,
    "The following classes exist in the current codebase but are not wired in any active "
    "controller or handler. They are legacy and must not be referenced in the new design. "
    "They will be deleted in the cleanup phase:")
add_bullet(doc, "CheckItemAccessHandler — old two-step approach, replaced by ItemAccessApplicableFilterHandlerNgsild.")
add_bullet(doc, "ResourcePolicyAuthorizationHandler — used a separate catalogue server that no longer exists.")
add_bullet(doc, "CatalogueVerticle, CatalogueServiceImpl, CatalogueClientImpl — call a separate catServerHost "
           "that is not deployed.")
add_bullet(doc, "AuthorizationServiceImpl (rs.authorization package) — depends on dead CatalogueService.")

# ════════════════════════════════════════════════════════════════════════════
# 3. SOLUTION OVERVIEW
# ════════════════════════════════════════════════════════════════════════════
add_divider(doc)
add_h1(doc, "3. Solution Overview")
add_divider(doc)

add_body(doc,
    "The solution introduces gRPC at every layer of the auth stack, without breaking the "
    "existing REST API. The key principle is: one internal gRPC channel from the Dataplane "
    "to the Controlplane carries all three internal communication needs.")

add_h2(doc, "3.1 Port Strategy")
add_three_col_table(doc,
    ["Port", "Server", "Purpose"],
    [
        ("8443", "Dataplane HTTP", "Existing REST API — unchanged, continues to work"),
        ("9090", "Dataplane gRPC", "NEW — external gRPC server for data queries from clients"),
        ("9000", "Controlplane gRPC", "NEW — internal gRPC for auth verification + revocation stream. Restricted to dataplane namespace only via Kubernetes NetworkPolicy."),
    ],
    [2.5, 4.0, 10.0]
)
doc.add_paragraph()

add_h2(doc, "3.2 Three Internal gRPC RPCs on the Controlplane")
add_body(doc,
    "The Controlplane must expose a new internal gRPC server on port 9000 with three RPCs:")

add_numbered(doc,
    "VerifyApp(appId, resourceId) → VerifyAppResponse — Called by the Dataplane when an "
    "AppId credential is presented. The Controlplane looks up the AppId in the app_credentials "
    "table, validates its status and expiry, fetches the constraints from app_constraints, "
    "and fetches catalogue metadata for the requested resource. It returns a complete access "
    "context: the owning user's UUID, their role (always consumer for AppId), the allowed "
    "query types, allowed attributes, access policy, and policy UUID.",
    bold_prefix="RPC 1:")
add_numbered(doc,
    "CheckItemAccess(userId, roles, issuer, resourceId) → CheckItemAccessResponse — Called "
    "by the Dataplane for JWT requests where the token does not carry embedded policies. "
    "The Dataplane validates the JWT signature locally (JWKS), extracts the validated claims "
    "(sub, realm_access.roles, iss), and sends them — NOT the raw token — to the Controlplane. "
    "The Controlplane looks up the user's ACL policy for the requested resource and returns "
    "the same access context fields as VerifyApp. This is the gRPC equivalent of the existing "
    "GET /iudx/v2/cat/item/access endpoint.",
    bold_prefix="RPC 2:")
add_numbered(doc,
    "WatchRevocations(serviceId) → stream of RevocationEvents — A persistent server-streaming "
    "RPC. When an AppId is revoked or its constraints change on the Controlplane, a revocation "
    "event is pushed to all connected Dataplane pods in real time. Each pod uses this to "
    "immediately evict the affected AppId from its local Caffeine cache and close any open "
    "StreamLatest gRPC streams for that AppId.",
    bold_prefix="RPC 3:")

add_h2(doc, "3.3 Three Auth Paths — All Producing the Same Result")
add_body(doc,
    "Regardless of the auth method, all three paths converge to a single GrpcAuthResult object "
    "that carries the same fields. This object is then used identically by the business logic.")

add_h3(doc, "Path A — AppId (x-app-id metadata or header)")
add_bullet(doc, "The Dataplane checks its local Caffeine cache (key: appId + resourceId, TTL 5 minutes).")
add_bullet(doc, "On a cache miss, it calls VerifyApp on the Controlplane via gRPC. "
           "The Controlplane performs all DB lookups and returns a typed Protobuf response.")
add_bullet(doc, "The result is cached and used to build a GrpcAuthResult.")
add_bullet(doc, "Role check is skipped — AppId is always consumer by definition.")

add_h3(doc, "Path B — JWT with embedded policies (fast path)")
add_bullet(doc, "The Dataplane validates the JWT signature locally against the JWKS cache. No network call.")
add_bullet(doc, "The token contains embedded policy data (policies field in claims). "
           "All required fields — query types, allowed attributes, access policy — are read "
           "directly from the JWT. Zero controlplane calls.")
add_bullet(doc, "Role is read from realm_access.roles in the JWT claims.")

add_h3(doc, "Path C — JWT without embedded policies (slow path)")
add_bullet(doc, "The Dataplane validates the JWT signature locally (JWKS). No raw token is forwarded.")
add_bullet(doc, "Validated claims (userId, roles, issuer) are sent to the Controlplane via "
           "the CheckItemAccess gRPC RPC.")
add_bullet(doc, "The Controlplane performs the ACL lookup and catalogue fetch, returning the "
           "same typed Protobuf response as VerifyApp.")
add_bullet(doc, "Not forwarding the raw JWT is a deliberate security decision — the Controlplane "
           "trusts the Dataplane's validation because port 9000 is network-policy-restricted to "
           "the Dataplane namespace (Phase 1) and protected by mTLS (Phase 2).")

add_h2(doc, "3.4 Shared Internal gRPC Channel")
add_body(doc,
    "A single persistent HTTP/2 connection (GrpcChannelManager) is maintained from the "
    "Dataplane to the Controlplane on port 9000. All three RPCs — VerifyApp, CheckItemAccess, "
    "and WatchRevocations — share this one channel via HTTP/2 multiplexing. "
    "There are no separate connections per RPC.")

add_h2(doc, "3.5 HTTP Path — Internally Backed by the Same gRPC Clients")
add_body(doc,
    "The existing REST API (port 8443) is NOT changed from the consumer's perspective. "
    "However, the new AppIdOrJwtAuthHandler security handler — which replaces the old "
    "MultiIssuerJwtAuthHandler — internally uses the same gRPC clients:")
add_bullet(doc, "AppId request via REST → AppVerifyGrpcClient.verifyApp() → same gRPC call, same cache.")
add_bullet(doc, "JWT slow path via REST → CatalogueAccessGrpcClient.checkItemAccess() → same gRPC call.")
add_bullet(doc, "JWT fast path via REST → local JWKS, no gRPC (unchanged).")
add_body(doc,
    "The result is converted to a Vert.x User principal object that is shaped identically to "
    "what the existing handler chain already reads from ctx.user().principal(). This means "
    "ItemAccessApplicableFilterHandlerNgsild, AuthorizationHandler, all controllers, and "
    "AuditingHandler require zero code changes.")

# ════════════════════════════════════════════════════════════════════════════
# 4. ACCEPTANCE CRITERIA
# ════════════════════════════════════════════════════════════════════════════
add_divider(doc)
add_h1(doc, "4. Acceptance Criteria")
add_divider(doc)

add_ac_table(doc, [
    "External gRPC client can call SearchEntities and GetLatest on Dataplane port 9090 using an AppId credential "
    "(x-app-id metadata). The Dataplane verifies the AppId via the VerifyApp gRPC RPC to the Controlplane.",

    "External gRPC client can call SearchEntities and GetLatest on Dataplane port 9090 using a JWT credential "
    "(authorization: Bearer metadata). Both fast path (embedded policies) and slow path (CheckItemAccess gRPC) work correctly.",

    "External REST client (port 8443) continues to work exactly as before for JWT. No regression in any existing API endpoint.",

    "External REST client (port 8443) with X-App-Id header is authenticated via the same VerifyApp gRPC call "
    "to the Controlplane and can access data according to the AppId's constraints.",

    "AppId verification results are cached in Caffeine (key: appId + resourceId, TTL 5 minutes). "
    "The same request within the TTL does not trigger a second VerifyApp call.",

    "When an AppId is revoked on the Controlplane, the WatchRevocations stream delivers a RevocationEvent to the "
    "Dataplane within 5 seconds. The Dataplane evicts all cache entries for that AppId. "
    "The next request with that AppId returns UNAUTHENTICATED.",

    "When an AppId's constraints are changed on the Controlplane (e.g., allowed attributes updated), a "
    "CONSTRAINT_CHANGED revocation event is pushed and the Dataplane cache is evicted. "
    "The next request fetches fresh constraints.",

    "WatchRevocations stream reconnects automatically with exponential backoff (max 60s) if the connection to "
    "the Controlplane is lost.",

    "Audit log for every AppId request contains a real user UUID (not a fake placeholder), the issuer "
    "'dx-controlplane', and the auth method 'APP_KEY'.",

    "AppId is never logged in full. Only the first 8 characters followed by **** appear in any log line.",

    "Role check (consumer / delegate) passes for AppId automatically without any explicit role field "
    "on the AppId credential itself. Role check for JWT reads from the validated JWT claims.",

    "ItemAccessApplicableFilterHandlerNgsild, AuthorizationHandler, LatestController, "
    "NGSILDSearchController, and AuditingHandler have zero code changes.",

    "gRPC reflection is disabled on port 9090 in production configuration.",

    "All gRPC calls from Dataplane to Controlplane (port 9000) enforce a configurable deadline "
    "(default 3 seconds). Timeout returns DEADLINE_EXCEEDED to the caller.",

    "Controlplane gRPC services on port 9000 are not reachable from outside the Dataplane Kubernetes namespace. "
    "A NetworkPolicy enforces this.",
])
doc.add_paragraph()

# ════════════════════════════════════════════════════════════════════════════
# 5. TECHNICAL SCOPE
# ════════════════════════════════════════════════════════════════════════════
add_divider(doc)
add_h1(doc, "5. Technical Scope")
add_divider(doc)

add_h2(doc, "5.1 Protobuf Contracts (New — Shared Between Controlplane and Dataplane)")
add_body(doc,
    "Three new proto files must be written and agreed upon before any implementation starts. "
    "These define the typed contracts for all internal communication:")
add_bullet(doc, "app_verify.proto — defines AppVerifyService with the VerifyApp RPC and its "
           "request/response messages (appId, resourceId, userId, roles, queryTypes, "
           "allowedAttributes, accessPolicy, policyId, expiry).",
           bold_prefix="1.")
add_bullet(doc, "catalogue_access.proto — defines CatalogueAccessService with the CheckItemAccess "
           "RPC and its request/response messages (userId, roles, issuer, resourceId, delegation "
           "fields in request; queryTypes, allowedAttributes, accessPolicy, policies in response).",
           bold_prefix="2.")
add_bullet(doc, "app_revocation.proto — defines AppRevocationService with WatchRevocations "
           "server-streaming RPC and RevocationEvent message (appId, reason, timestamp).",
           bold_prefix="3.")
add_bullet(doc, "data_service.proto — defines the external DataService: SearchEntities, GetLatest "
           "(unary), and StreamLatest (server-streaming). Entity payloads are JSON-encoded bytes "
           "in Phase 1 (typed Protobuf entities are a future phase).",
           bold_prefix="4.")

add_h2(doc, "5.2 Controlplane — New Classes Required")
add_three_col_table(doc,
    ["Class / Component", "Type", "Responsibility"],
    [
        ("AppVerifyGrpcService", "New", "Implements AppVerifyService gRPC. Queries app_credentials and app_constraints tables. Fetches catalogue metadata. Returns typed VerifyAppResponse."),
        ("CatalogueAccessGrpcService", "New", "Implements CatalogueAccessService gRPC. Accepts validated JWT claims (not raw token). Looks up user ACL and catalogue info. Returns typed CheckItemAccessResponse."),
        ("AppRevocationGrpcService", "New", "Implements AppRevocationService gRPC. Maintains active WatchRevocations streams. Publishes RevocationEvent to all connected Dataplane pods on revocation or constraint change."),
        ("AppCredentialRepository (change)", "Modified", "Add call to AppRevocationGrpcService.publish() on revokeAppId(). Trigger REVOKED event."),
        ("AppConstraintRepository (change)", "Modified", "Add call to AppRevocationGrpcService.publish() on updateConstraints(). Trigger CONSTRAINT_CHANGED event."),
        ("Internal gRPC Server bootstrap", "New", "Start gRPC server on port 9000. Register all three services. Apply TLS configuration."),
    ],
    [4.5, 1.8, 10.2]
)
doc.add_paragraph()

add_h2(doc, "5.3 Dataplane — New Classes Required")
add_three_col_table(doc,
    ["Class", "Type", "Responsibility"],
    [
        ("GrpcServerVerticle", "New", "Starts external gRPC server on port 9090. Registers callHandler for each DataService method using GrpcAuthPipeline."),
        ("GrpcAuthPipeline", "New", "Core auth logic — handles AppId path (VerifyApp gRPC call + cache) and JWT path (local JWKS + optional CheckItemAccess gRPC call). Used by both gRPC callHandlers and the HTTP security handler."),
        ("GrpcAuthResult", "New", "Immutable record carrying all auth context: userId, issuer, roles, authMethod, accessPolicy, queryTypes, allowedAttributes, policyId, expiryEpoch, resourceId. Factory methods for all three auth paths."),
        ("AppVerifyGrpcClient", "New", "Vert.x gRPC client wrapping VerifyApp RPC. Manages deadline, error mapping."),
        ("CatalogueAccessGrpcClient", "New", "Vert.x gRPC client wrapping CheckItemAccess RPC."),
        ("AppRevocationStreamHandler", "New", "Subscribes to WatchRevocations at startup. On RevocationEvent: evicts Caffeine cache + closes affected StreamLatest streams. Reconnects with exponential backoff."),
        ("StreamRevocationRegistry", "New", "Tracks open StreamLatest response streams per AppId. Closes affected streams when revocation event arrives."),
        ("GrpcDataServiceImpl", "New", "Business logic for SearchEntities, GetLatest, StreamLatest. Accepts GrpcAuthResult and applies allowed query types / attribute filters. Delegates to existing SearchService and LatestService."),
        ("GrpcAuditingHandler", "New", "Publishes audit events to RabbitMQ via existing DataBrokerService. Equivalent of the HTTP AuditingHandler."),
        ("GrpcErrorMapper", "New", "Maps DX exceptions to Vert.x GrpcStatus codes."),
        ("GrpcChannelManager", "New", "Manages the single persistent gRPC channel to Controlplane port 9000. Handles TLS and reconnection."),
        ("AppIdOrJwtAuthHandler", "New", "Replaces MultiIssuerJwtAuthHandler as the OpenAPI security handler in AbstractApiServerVerticle. Calls GrpcAuthPipeline internally. Converts GrpcAuthResult to Vert.x User principal for the HTTP handler chain."),
    ],
    [4.5, 1.8, 10.2]
)
doc.add_paragraph()

add_h2(doc, "5.4 Dataplane — Modified Classes")
add_three_col_table(doc,
    ["Class", "Location", "Change Required"],
    [
        ("AbstractApiServerVerticle", "dx-common", "Replace MultiIssuerJwtAuthHandler with AppIdOrJwtAuthHandler as the 'authorization' security handler registration."),
        ("ApiServerVerticle", "dx-dataplane-rs", "Start GrpcServerVerticle and AppRevocationStreamHandler from configureAdditionalRoutes(). Inject shared GrpcAuthPipeline instance."),
        ("config.json", "dx-dataplane-rs", "Add: grpcExternalPort (9090), grpcExternalTls (bool), controlplaneGrpcHost, controlplaneGrpcPort (9000), grpcCallDeadlineMs (default 3000)."),
        ("pom.xml", "dx-dataplane-rs", "Add dependencies: vertx-grpc-server, vertx-grpc-client, protobuf-maven-plugin for proto compilation."),
    ],
    [4.5, 3.5, 8.5]
)
doc.add_paragraph()

add_h2(doc, "5.5 Dataplane — Classes With ZERO Changes")
add_body(doc,
    "The following classes are NOT modified. The design specifically preserves them as-is "
    "by ensuring the new auth pipeline produces the same principal shape they already expect:")
add_bullet(doc, "ItemAccessApplicableFilterHandlerNgsild — fast path triggers automatically "
           "because GrpcAuthResult is converted to a principal with the 'policies' field present.")
add_bullet(doc, "AuthorizationHandler.forRoles() — sees realm_access.roles from the converted principal.")
add_bullet(doc, "LatestController, NGSILDSearchController, DownloadController — all ctx.user() calls work.")
add_bullet(doc, "AuditingHandler — ctx.user().subject() returns the real user UUID from the database.")
add_bullet(doc, "SearchService, LatestService — business logic is auth-agnostic.")
add_bullet(doc, "DataBrokerService — unchanged.")
add_bullet(doc, "ElasticsearchService — unchanged.")

# ════════════════════════════════════════════════════════════════════════════
# 6. IN SCOPE / OUT OF SCOPE
# ════════════════════════════════════════════════════════════════════════════
add_divider(doc)
add_h1(doc, "6. Scope")
add_divider(doc)

add_h2(doc, "In Scope")
for item in [
    "External gRPC server on Dataplane (port 9090) — SearchEntities, GetLatest, StreamLatest.",
    "AppId authentication via gRPC VerifyApp RPC (for both REST and gRPC external clients).",
    "JWT authentication slow path via gRPC CheckItemAccess RPC (replaces HTTP /cat/item/access call).",
    "JWT fast path — no change, already works via embedded policies in token.",
    "AppId Caffeine cache on Dataplane (key: appId + resourceId, TTL 5 minutes).",
    "WatchRevocations persistent stream — revocation push from Controlplane to Dataplane.",
    "CONSTRAINT_CHANGED revocation event when AppId constraints are updated.",
    "Exponential backoff reconnection for WatchRevocations stream.",
    "StreamLatest JWT per-event expiry check.",
    "StreamLatest AppId revocation close via StreamRevocationRegistry.",
    "Kubernetes NetworkPolicy to restrict Controlplane port 9000 to Dataplane namespace only.",
    "Audit logging for AppId requests with real user identity from the database.",
    "AppIdOrJwtAuthHandler replacing MultiIssuerJwtAuthHandler as HTTP security handler.",
]:
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Inches(0.3)
    r = p.add_run("✓  " + item)
    r.font.size = Pt(10.5)
    r.font.color.rgb = GREEN

doc.add_paragraph()
add_h2(doc, "Out of Scope")
for item in [
    "Changes to the external REST API contract (port 8443). All existing endpoints unchanged.",
    "JWT validation protocol — still local JWKS. No round-trip to Controlplane for signature.",
    "AppId issuance / registration UI or API — managed separately on Controlplane.",
    "Delegate flows over gRPC — delegation support on the CheckItemAccess RPC is designed but not tested in Phase 1.",
    "Full typed Protobuf for NGSILD entity payloads — entities remain JSON-encoded bytes in Phase 1.",
    "Rate limiting (Phase 3 hardening).",
    "mTLS on the internal gRPC channel (Phase 2).",
    "JWT slow path caching — not cached in this design (re-evaluated after Phase 2 performance data).",
    "Publish API (POST /ngsi-ld/v1/publish) — AppId is explicitly not permitted for publish. JWT only.",
]:
    p = doc.add_paragraph(style='List Bullet')
    p.paragraph_format.left_indent = Inches(0.3)
    r = p.add_run("✗  " + item)
    r.font.size = Pt(10.5)
    r.font.color.rgb = RED

# ════════════════════════════════════════════════════════════════════════════
# 7. OPEN QUESTIONS
# ════════════════════════════════════════════════════════════════════════════
add_divider(doc)
add_h1(doc, "7. Open Questions — Manager Decision Required")
add_divider(doc)

add_three_col_table(doc,
    ["#", "Question", "Options / Impact"],
    [
        ("OQ1", "Should AppId support StreamLatest (real-time streaming)?",
         "YES: Adds StreamRevocationRegistry — streams must be closed immediately on revocation. "
         "NO: AppId supports only unary SearchEntities and GetLatest. Simpler Phase 1. "
         "Recommended: defer to Phase 3 after unary is stable."),

        ("OQ2", "TLS termination for external gRPC port 9090 — at the Dataplane or at the ingress?",
         "Dataplane terminates TLS: Dataplane reads keystore, simpler topology. "
         "Ingress (Envoy/nginx) terminates, Dataplane sees plain h2c: simpler Dataplane code, "
         "requires trusted internal network. "
         "Decision needed before GrpcServerVerticle TLS config is written."),

        ("OQ3", "mTLS for internal gRPC (port 9000) — Phase 1 or Phase 2?",
         "Phase 1: stronger security from the start but adds certificate management overhead early. "
         "Phase 2: NetworkPolicy is sufficient for Phase 1; add mTLS later. "
         "Recommended: Phase 2 — NetworkPolicy + K8s namespace isolation is adequate for Phase 1."),

        ("OQ4", "JWT slow path caching — add a short-TTL cache for CheckItemAccess responses?",
         "No cache (current): safe, always fresh, extra gRPC call per uncached JWT request. "
         "Cache by userId:resourceId (30s TTL): faster, but ACL changes take up to 30s to reflect. "
         "Requires ACL-change invalidation mechanism (not currently designed). "
         "Recommended: no cache in Phase 1; revisit after performance data."),

        ("OQ5", "Who builds the Controlplane gRPC services — Controlplane team or jointly?",
         "Controlplane team owns AppVerifyGrpcService and CatalogueAccessGrpcService. "
         "Dataplane team owns GrpcAuthPipeline and gRPC clients. "
         "Proto files must be agreed jointly before either team starts coding. "
         "Critical path dependency — agree protos first."),

        ("OQ6", "Should the HTTP path (port 8443) also use gRPC clients in Phase 1, "
         "or keep HTTP calls to the Controlplane for the HTTP path in Phase 1?",
         "All-gRPC from Phase 1: consistent, one set of clients, but more scope. "
         "HTTP path keeps HTTP in Phase 1, migrated in Phase 2: less Phase 1 scope. "
         "Recommended: all-gRPC from Phase 1 to avoid maintaining two verification code paths."),
    ],
    [0.6, 5.5, 10.4]
)
doc.add_paragraph()

# ════════════════════════════════════════════════════════════════════════════
# 8. DEPENDENCIES
# ════════════════════════════════════════════════════════════════════════════
add_divider(doc)
add_h1(doc, "8. Dependencies")
add_divider(doc)

add_three_col_table(doc,
    ["Dependency", "Owner", "Blocking?"],
    [
        ("Proto files agreed (app_verify.proto, catalogue_access.proto, app_revocation.proto, data_service.proto)",
         "Both teams jointly", "YES — nothing can be coded until protos are frozen."),
        ("Controlplane gRPC server infrastructure — gRPC server on port 9000, TLS config",
         "Controlplane team", "YES — Dataplane gRPC clients cannot be tested without it."),
        ("AppVerifyGrpcService implementation on Controlplane",
         "Controlplane team", "YES for Phase 1 AppId."),
        ("CatalogueAccessGrpcService implementation on Controlplane",
         "Controlplane team", "YES for Phase 2 JWT slow path."),
        ("AppRevocationGrpcService on Controlplane",
         "Controlplane team", "YES for cache invalidation."),
        ("Kubernetes NetworkPolicy for port 9000",
         "DevOps / Infrastructure", "YES for security before any production deployment."),
        ("vertx-grpc-server and vertx-grpc-client library versions agreed",
         "Dataplane team", "YES — affects pom.xml and Vert.x version constraints."),
        ("AppId credential management (how admins issue and revoke AppIds on Controlplane)",
         "Controlplane team / Product", "NO for Phase 1 coding, but needed before any consumer can use AppId."),
    ],
    [5.5, 4.0, 7.0]
)
doc.add_paragraph()

# ════════════════════════════════════════════════════════════════════════════
# 9. RISKS
# ════════════════════════════════════════════════════════════════════════════
add_divider(doc)
add_h1(doc, "9. Risks and Mitigations")
add_divider(doc)

add_three_col_table(doc,
    ["Risk", "Likelihood", "Mitigation"],
    [
        ("Controlplane gRPC services are delayed — blocks Dataplane Phase 1 testing.",
         "Medium",
         "Use a mock gRPC server (in-process Vert.x stub) for Dataplane development and unit testing. "
         "Integration testing only needs the real Controlplane."),

        ("WatchRevocations stream drops silently in production (network partition).",
         "Medium",
         "Heartbeat / ping on the stream every 30s. Dataplane detects stream death via endHandler "
         "and reconnects with backoff. Caffeine TTL (5 min) limits the blast radius."),

        ("AppId is used for the Publish API (POST /ngsi-ld/v1/publish) by a consumer.",
         "Low",
         "AppIdOrJwtAuthHandler explicitly rejects AppId on Publish endpoint with HTTP 403. "
         "Same rejection in GrpcDataServiceImpl if Publish is ever added to the gRPC API."),

        ("Proto contract changes after coding has started (breaking change).",
         "Low-Medium",
         "Freeze protos before Phase 1 coding. Any breaking change requires a version bump "
         "(package dx.auth.v2) and a coordinated release."),

        ("gRPC call to Controlplane times out under load — cascading failure.",
         "Low",
         "Caffeine cache absorbs most requests. Deadline is configurable (default 3s). "
         "On timeout: return DEADLINE_EXCEEDED to caller, do not retry automatically on the hot path."),

        ("TLS certificate rotation on port 9090 causes external client disconnects.",
         "Low",
         "Use ingress TLS termination (OQ2) to decouple cert rotation from Dataplane restarts. "
         "Or implement graceful reload of TLS context."),
    ],
    [5.5, 2.5, 8.5]
)
doc.add_paragraph()

# ════════════════════════════════════════════════════════════════════════════
# 10. PHASED ROLLOUT
# ════════════════════════════════════════════════════════════════════════════
add_divider(doc)
add_h1(doc, "10. Phased Rollout Plan")
add_divider(doc)

phases = [
    ("Phase 1 — AppId Authentication (Estimated: 4–5 weeks)", [
        "CONTROLPLANE: Implement AppVerifyGrpcService.VerifyApp RPC.",
        "CONTROLPLANE: Implement AppRevocationGrpcService.WatchRevocations stream.",
        "CONTROLPLANE: Fire REVOKED event from AppCredentialRepository on revoke.",
        "CONTROLPLANE: Fire CONSTRAINT_CHANGED event from AppConstraintRepository on update.",
        "INFRA: Kubernetes NetworkPolicy to restrict port 9000 to Dataplane namespace.",
        "DATAPLANE: GrpcChannelManager — single persistent channel to Controlplane port 9000.",
        "DATAPLANE: AppVerifyGrpcClient wrapping VerifyApp RPC.",
        "DATAPLANE: AppRevocationStreamHandler — subscribes to WatchRevocations, backoff reconnect.",
        "DATAPLANE: GrpcAuthPipeline — AppId verification path only.",
        "DATAPLANE: GrpcAuthResult record with fromVerifyApp() factory method.",
        "DATAPLANE: AppIdOrJwtAuthHandler — HTTP security handler using GrpcAuthPipeline for AppId.",
        "DATAPLANE: GrpcServerVerticle — external gRPC server (port 9090), SearchEntities + GetLatest only.",
        "DATAPLANE: GrpcDataServiceImpl, GrpcAuditingHandler, GrpcErrorMapper.",
        "DATAPLANE: TLS on port 9090.",
    ]),
    ("Phase 2 — JWT Authentication over gRPC (Estimated: 2–3 weeks)", [
        "CONTROLPLANE: Implement CatalogueAccessGrpcService.CheckItemAccess RPC.",
        "DATAPLANE: CatalogueAccessGrpcClient wrapping CheckItemAccess RPC.",
        "DATAPLANE: GrpcAuthPipeline — add JWT slow path using CatalogueAccessGrpcClient.",
        "DATAPLANE: GrpcAuthResult fromCheckItemAccess() and fromJwtFastPath() factory methods.",
        "DATAPLANE: AppIdOrJwtAuthHandler — JWT slow path now calls CatalogueAccessGrpcClient "
        "(replaces HTTP /cat/item/access call on HTTP path).",
    ]),
    ("Phase 3 — Streaming and Hardening (Estimated: 2 weeks)", [
        "DATAPLANE: GrpcDataServiceImpl.streamLatest() — JWT per-event expiry check.",
        "DATAPLANE: StreamRevocationRegistry — AppId StreamLatest revocation close.",
        "DATAPLANE: Server-side deadline enforcement on port 9090 incoming requests.",
        "DATAPLANE: Disable gRPC reflection on port 9090 in production config.",
        "DATAPLANE: Rate limiting in GrpcAuthPipeline (per appId, per userId).",
    ]),
    ("Phase 4 — Security Hardening and Cleanup (Estimated: 1–2 weeks)", [
        "INFRA: mTLS on internal gRPC channel (port 9000) — Controlplane and Dataplane exchange certs.",
        "DATAPLANE: Delete dead code — CheckItemAccessHandler, ResourcePolicyAuthorizationHandler, "
        "CatalogueVerticle, CatalogueServiceImpl, CatalogueClientImpl, AuthorizationServiceImpl (rs.authorization).",
        "REVIEW: Evaluate JWT slow path caching (userId:resourceId, 30s TTL) based on Phase 2 load data.",
    ]),
]

for phase_title, items in phases:
    add_h2(doc, phase_title)
    for item in items:
        add_bullet(doc, item)
    doc.add_paragraph()

# ════════════════════════════════════════════════════════════════════════════
# 11. DEFINITION OF DONE
# ════════════════════════════════════════════════════════════════════════════
add_divider(doc)
add_h1(doc, "11. Definition of Done")
add_divider(doc)

for item in [
    "All acceptance criteria in Section 4 are verified with integration tests.",
    "Proto files are committed to a shared repository and consumed by both Controlplane and Dataplane builds.",
    "Unit tests cover GrpcAuthPipeline for all three auth paths (AppId, JWT fast, JWT slow), "
    "including cache hit, cache miss, revocation, timeout, and role-check scenarios.",
    "Integration test: AppId request via external gRPC → VerifyApp gRPC → Elasticsearch → response.",
    "Integration test: JWT request via external gRPC (slow path) → CheckItemAccess gRPC → Elasticsearch → response.",
    "Integration test: AppId revoked on Controlplane → RevocationEvent received → cache evicted → next request returns UNAUTHENTICATED.",
    "Existing REST API regression tests pass without modification.",
    "No full AppId UUID appears in any log line.",
    "NetworkPolicy applied and verified: port 9000 not reachable from outside the Dataplane namespace.",
    "Peer code review completed for all new classes listed in Section 5.2 and 5.3.",
    "Design document updated to reflect any implementation deviations from this story.",
]:
    add_bullet(doc, item)

# ════════════════════════════════════════════════════════════════════════════
# FOOTER
# ════════════════════════════════════════════════════════════════════════════
doc.add_paragraph()
add_divider(doc)
p = doc.add_paragraph()
p.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = p.add_run(f"Prepared by Ankit Singh  ·  {today}  ·  Version 1.0 Draft  ·  For Manager Approval")
r.font.size = Pt(9)
r.font.color.rgb = RGBColor(0x88, 0x88, 0x88)
r.italic = True

doc.save(OUTPUT)
print(f"✓ Generated: {OUTPUT}")
